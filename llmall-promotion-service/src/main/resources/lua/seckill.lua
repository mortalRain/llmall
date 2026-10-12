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

  KEYS[1] 库存键       seckill:stock:{voucherId}      值为剩余库存
  KEYS[2] 已购集合键   seckill:ordered:{voucherId}    成员为 userId
  ARGV[1] userId
  ARGV[2] 库存键缺失时的兜底初始值（来自 DB seckill_voucher.stock）
  ARGV[3] 键过期秒数（秒杀窗口内绝不过期，窗口结束后自动回收，避免 key 无限增长）
]]

local stockKey = KEYS[1]
local orderedKey = KEYS[2]
local userId = ARGV[1]
local initStock = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

-- 1. 缓存兜底初始化：Redis 重启或首次访问时库存键不存在，
--    用 DB 里的真实库存 SETNX 一次。用 NX 而不是 SET，
--    是为了避免并发请求把已经被扣减过的库存重置回初始值（那会凭空造出库存）。
if redis.call('EXISTS', stockKey) == 0 then
    redis.call('SET', stockKey, initStock, 'NX')
    redis.call('EXPIRE', stockKey, ttlSeconds)
end

-- 2. 一人一单的第一道闸：已购集合（Set）判定，O(1) 且天然去重。
--    它在 DB 唯一键 uk_user_voucher 之前生效，作用是把绝大多数重复请求挡在数据库之外。
if redis.call('SISMEMBER', orderedKey, userId) == 1 then
    return 2
end

-- 3. 库存判定
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

-- 4. 扣库存 + 记名（同一脚本内，不存在中间态：不会出现"扣了库存但没记名"）
redis.call('DECR', stockKey)
redis.call('SADD', orderedKey, userId)
redis.call('EXPIRE', orderedKey, ttlSeconds)

return 0
