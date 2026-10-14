--[[
  秒杀预扣减脚本（库存 + 一人一单 的判断与扣减，全部在一次 Redis 调用内完成）

  【为什么必须用 Lua，而不是在 Java 里调多条 Redis 命令】
  Java 侧"先 GET 看库存、再 DECR"或"先 SISMEMBER、再 SADD"这类写法，两条命令之间存在时间窗：
  高并发下同一商品的多个请求可以同时读到"库存还有"，于是超卖；
  同一用户的两个并发请求也可以同时读到"没买过"，于是一人多单。
  Redis 执行 Lua 时是单线程的，脚本内的所有命令不会被其它请求插入，
  因此"判断 + 扣减 + 记名"被压缩成一个不可分割的操作 —— 这是秒杀场景唯一可靠的原子性来源。

  【返回值约定】业务分支用返回值表达，不用异常：
    0 = 预扣成功（库存 -1 且 userId 记入已购集合）
    1 = 库存不足
    2 = 该用户已抢购过（重复下单）
  Java 侧按数字分支抛对应的业务异常，脚本保持无副作用语义。

  KEYS[1] 库存键       seckill:stock:{voucherId}      值为剩余可抢数
  KEYS[2] 已购集合键   seckill:ordered:{voucherId}    成员为 userId
  KEYS[3] 已发放键     seckill:issued:{voucherId}     Redis 累计放出的名额（用于重建库存）
  ARGV[1] userId
  ARGV[2] **安全**的引导库存（来自 DB 的权威扣减结果，已扣除 DB 已确认发放的部分）
  ARGV[3] 键过期秒数（秒杀窗口内绝不过期，窗口结束后自动回收，避免 key 无限增长）
]]

local stockKey = KEYS[1]
local orderedKey = KEYS[2]
local issuedKey = KEYS[3]
local userId = ARGV[1]
local initStock = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

-- 1. 缓存兜底初始化：Redis 丢过数据（重启 / 被清 / allkeys-lru 淘汰）时库存键不存在。
--
--    【这里原来的写法会造成超卖】原脚本直接 `SET stockKey initStock NX`，而 initStock
--    传的是 DB 的 stock 列。问题在于 DB 的 stock 是**权威扣减之后的剩余值**：
--    库里本来有 100，异步落库已经卖出去 50，DB.stock 就是 50。
--    Redis 一丢数据，库存被重置成 50，可此时 MQ 里可能还压着一批"已预扣、待落库"的请求，
--    它们落库时再扣 50 → 一共放出 100 + 50 = 150 个名额，超卖 50。
--    （现役 Redis 是 maxmemory 0 + noeviction，但 docker 版编排用的是 allkeys-lru，
--      而且重启不加载 RDB —— "库存键消失"是随时可能发生的真实场景，不是理论风险。）
--
--    【改后的做法】用 seckill:issued:{voucherId} 记录"这张券一共放出去过多少名额"，
--    每次预扣成功 +1、每次回补 -1，它与库存键在**同一个脚本里**增减，永远不会不同步。
--    引导时按 issued 反推剩余，保证恒等式 stock + issued = 该券总名额 在重建后依然成立：
--
--      · issued 缺失（Redis 整体被清）→ 视作 0，等同于"从 DB 剩余量重新开始"。
--        Java 侧传进来的 ARGV[2] 已经扣掉了 DB 已确认发放的部分（见 warmUpSeckillCache），
--        所以这一步是安全的：后续落库消耗的正是同一批已发放名额，不会再动库存键。
--      · issued > initStock（Redis 认识的已发放比 DB 剩余量还大 → 存在尚未落库的预扣）
--        → 剩余置 0。**宁可少卖也不超卖**：这些预扣落库后 DB.stock 会补上，
--          运维重新预热即可恢复；超卖的代价是资损，少卖的代价只是让用户重试。
--      · 其余情况 → 剩余 = initStock - issued（正常路径下两者一致，结果为 0，与旧行为等价）。
--
--    注意 NX 语义保留：并发请求同时发现键不存在时，只有一个能真正写入。
--    这里与 Java 侧的 BOOTSTRAP_SCRIPT（预热路径专用）是**同一套夹取规则的两个副本**：
--    预热路径走 BOOTSTRAP_SCRIPT（只建键、不消耗名额），抢购路径走本脚本的这个分支
--    （兜住"预热还没跑就来了第一个请求"）。两处规则必须一起改。
if redis.call('EXISTS', stockKey) == 0 then
    redis.call('SET', stockKey, initStock, 'NX')
    local issued = tonumber(redis.call('GET', issuedKey)) or 0
    if issued < 0 then issued = 0 end
    local bootstrapStock = initStock - issued
    if bootstrapStock < 0 then bootstrapStock = 0 end
    redis.call('SET', stockKey, bootstrapStock)
    -- 让恒等式 stock + issued = initStock 重新成立
    redis.call('SET', issuedKey, initStock - bootstrapStock)
    redis.call('EXPIRE', stockKey, ttlSeconds)
    redis.call('EXPIRE', issuedKey, ttlSeconds)
end

-- 2. 一人一单的第一道闸：已购集合（Set）判定，O(1) 且天然去重。
--    它在 DB 唯一键 uk_user_voucher 之前生效，作用是把绝大多数重复请求挡在数据库之外。
if redis.call('SISMEMBER', orderedKey, userId) == 1 then
    return 2
end

-- 3. 库存判定
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    -- 读不到就当没库存（fail-closed）：绝不用"猜"出来的库存放行，
    -- 最多是这一轮少卖，由 DB 的最终账本与对账兜底。
    return 1
end

-- 4. 扣库存 + 记名 + 记已发放（同一脚本内，不存在中间态：
--    不会出现"扣了库存但没记名"，也不会出现"记了名但没记已发放"）
redis.call('DECR', stockKey)
redis.call('SADD', orderedKey, userId)
redis.call('INCR', issuedKey)
redis.call('EXPIRE', orderedKey, ttlSeconds)
redis.call('EXPIRE', issuedKey, ttlSeconds)

return 0
