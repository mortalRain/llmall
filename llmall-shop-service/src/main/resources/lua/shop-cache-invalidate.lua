-- Revocation and eviction are one operation: old writers cannot refill after a successful invalidation.
redis.call('DEL', KEYS[1])
return redis.call('DEL', KEYS[2])
