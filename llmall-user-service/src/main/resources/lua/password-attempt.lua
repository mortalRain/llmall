-- Both keys are owned by the same account and purpose. No credentials are stored.
local action = ARGV[1]
local limit = tonumber(ARGV[2])
local lockSeconds = tonumber(ARGV[3])
local failureSeconds = tonumber(ARGV[4])
local ttl = redis.call('TTL', KEYS[2])
if ttl ~= -2 then
    -- A permanent legacy lock also blocks; requests never extend or remove it.
    if ttl > 0 then return ttl end
    return lockSeconds
end
if action == 'CHECK' then return 0 end
if action == 'SUCCESS' then
    redis.call('DEL', KEYS[1])
    return 0
end
if action == 'FAIL' then
    local failures = redis.call('INCR', KEYS[1])
    redis.call('EXPIRE', KEYS[1], failureSeconds)
    if failures >= limit then
        redis.call('SET', KEYS[2], '1', 'EX', lockSeconds)
        redis.call('DEL', KEYS[1])
        return lockSeconds
    end
    return 0
end
return redis.error_reply('invalid password attempt action')
