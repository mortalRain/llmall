--[[
  秒杀预扣减脚本：库存判定 + 一人一单判定 + 扣减，全部在一次 Redis 调用内完成。

  【为什么必须用 Lua】Java 侧"先 GET 库存再 DECR"、"先 SISMEMBER 再 SADD"这类写法，
  两条命令之间存在时间窗：并发请求会同时读到"还有库存""没买过"，于是超卖、一人多单。
  Redis 执行 Lua 期间不会被其它请求插入，判断与扣减因此被压缩成一个不可分割的操作 ——
  这是秒杀场景唯一可靠的原子性来源。

  【返回值约定】业务分支用返回值表达，不用异常，Java 侧按数字分流抛对应业务异常：
    0 = 预扣成功（库存 -1、userId 记入已购集合、已发放 +1）
    1 = 库存不足
    2 = 该用户已抢购过（重复下单）

  【fail 策略】一律 fail-closed：库存键读不到值或不足时当"没有库存"处理（return 1）。
  绝不用猜出来的库存放行 —— 最多这一轮少卖，最终由 DB 账本与对账兜底。

  KEYS[1] 库存键     seckill:stock:{voucherId}     值 = 剩余可抢数
  KEYS[2] 已购集合键 seckill:ordered:{voucherId}   成员 = userId
  KEYS[3] 已发放键   seckill:issued:{voucherId}    值 = Redis 累计放出的名额
  ARGV[1] userId
          取值：非空用户 ID 字符串
  ARGV[2] 总名额 T
          语义：该券可发放的总数量 = DB 剩余量 + DB 已发放行数（Java 侧 totalSeckillStock 计算）
          单位：张    取值：>= 0 的整数字符串
          【误传后果】传"剩余量"而非"总名额"会让脚本二次扣减：当已发放 >= T/2 时库存被夹到 0，
          DB 里还剩的券全部不可售，且不可自愈 —— 重新预热走同一口径，算出来仍然是错的。
  ARGV[3] 键过期秒数（TTL）
          单位：秒    取值：> 0 的整数
  ARGV[4] 已知已放出数量（DB 已发放行数，作为 Redis 丢数据时的保守下限）
          单位：张    取值：>= 0 的整数；Redis 侧读不到 issued 时按 0

  【多副本同步】本脚本的引导分支与 Java 侧 BOOTSTRAP_SCRIPT 是同一套夹取规则的两个副本：
  预热路径走 BOOTSTRAP_SCRIPT（只建键、不消耗名额），抢购路径走本分支（兜住"预热未跑就来请求"）。
  改一处必须同步另一处，否则两条路径会按不同口径引导库存。
]]

local stockKey = KEYS[1]
local orderedKey = KEYS[2]
local issuedKey = KEYS[3]
local userId = ARGV[1]
local totalStock = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])
local knownIssued = tonumber(ARGV[4]) or 0
if knownIssued < 0 then knownIssued = 0 end

-- 1. 缓存兜底初始化：库存键不存在（Redis 重启 / 被清 / 被淘汰）时，按已发放数反推剩余。
--
--    【为什么不能直接用 DB 的 stock 列当库存】DB 的 stock 是权威扣减之后的剩余值：
--    库里原有 100、异步落库已卖出 50，DB.stock 就是 50。Redis 一丢数据就把库存重置成 50，
--    而 MQ 里可能还压着"已预扣、待落库"的请求，它们落库时会再扣一次 —— 结果超卖。
--    （现役 Redis 是 maxmemory 0 + noeviction，但 docker 版编排用 allkeys-lru 且重启不加载 RDB，
--      "库存键消失"是随时可能发生的真实场景，不是理论风险。）
--
--    【做法】seckill:issued 记录"这张券一共放出去过多少名额"：每次预扣 +1、每次回补 -1，
--    它与库存键在同一个脚本里增减，永远不会不同步。引导时按 issued 反推剩余，保证恒等式成立：
--      剩余 = 总名额 T - 已发放 issued
--
--    【取值规则】issued 缺失（Redis 整体被清）时，取 max(Redis issued, ARGV[4] DB 已发放行数)。
--    存在尚未落库的在途预扣时，宁可少卖也不把已经卖出去的名额重新放出来。
if redis.call('EXISTS', stockKey) == 0 then
    local issued = tonumber(redis.call('GET', issuedKey)) or 0
    if issued < 0 then issued = 0 end
    -- 取较大值：Redis 丢数据时 issued 可能为 0，只用它会把已卖出的名额重新放出来
    if knownIssued > issued then issued = knownIssued end
    local bootstrapStock = totalStock - issued
    if bootstrapStock < 0 then bootstrapStock = 0 end
    redis.call('SET', stockKey, bootstrapStock)
    -- 恒等式：stock + issued = 总名额 T
    redis.call('SET', issuedKey, issued)
    redis.call('EXPIRE', stockKey, ttlSeconds)
    redis.call('EXPIRE', issuedKey, ttlSeconds)
end

-- 2. 一人一单的第一道闸：已购集合判定，O(1) 且天然去重。
--    它在 DB 唯一键 uk_user_voucher 之前生效，作用是把绝大多数重复请求挡在数据库之外。
if redis.call('SISMEMBER', orderedKey, userId) == 1 then
    return 2
end

-- 3. 库存判定：剩余库存必须 >= 本次扣减数量才放行；本脚本每次固定扣 1，写成 stock >= 1。
--    读不到值或不足一律当"没库存"（fail-closed），与头部 fail 策略一致。
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

-- 4. 扣库存 + 记名 + 记已发放：同一脚本内完成，不存在
--    "扣了库存但没记名""记了名但没记已发放"这类中间态。
redis.call('DECR', stockKey)
redis.call('SADD', orderedKey, userId)
redis.call('INCR', issuedKey)
redis.call('EXPIRE', orderedKey, ttlSeconds)
redis.call('EXPIRE', issuedKey, ttlSeconds)

return 0
