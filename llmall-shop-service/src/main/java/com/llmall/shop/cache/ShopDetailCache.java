package com.llmall.shop.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.shop.domain.bo.RedisData;
import com.llmall.shop.domain.bo.RebuildReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import static com.llmall.shop.constant.ShopRedisConstants.*;

/** Detail caching policy only; the caller retains database and DTO ownership. */
@Slf4j
@Component
public class ShopDetailCache {
    private static final DefaultRedisScript<Long> PUBLISH = script("lua/shop-cache-publish.lua");
    private static final DefaultRedisScript<Long> INVALIDATE = script("lua/shop-cache-invalidate.lua");
    private static final DefaultRedisScript<Long> REMOVE_IF_MATCHES = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Executor rebuilds;
    private final ShopCacheFallback fallback;
    private final AtomicLong lastWarning = new AtomicLong();

    @Value("${llmall.shop.cache.ttl-minutes:"+CACHE_TTL_MINUTES+"}") private long ttlMinutes = CACHE_TTL_MINUTES;
    @Value("${llmall.shop.cache.ttl-jitter-minutes:"+CACHE_TTL_JITTER_MINUTES+"}") private long jitterMinutes = CACHE_TTL_JITTER_MINUTES;
    @Value("${llmall.shop.cache.physical-extra-minutes:"+CACHE_PHYSICAL_EXTRA_MINUTES+"}") private long extraMinutes = CACHE_PHYSICAL_EXTRA_MINUTES;
    @Value("${llmall.shop.cache.null-ttl-minutes:"+NULL_CACHE_TTL_MINUTES+"}") private long nullMinutes = NULL_CACHE_TTL_MINUTES;
    @Value("${llmall.shop.cache.null-ttl-jitter-minutes:"+NULL_CACHE_TTL_JITTER_MINUTES+"}") private long nullJitter = NULL_CACHE_TTL_JITTER_MINUTES;
    @Value("${llmall.shop.cache.lock-ttl-seconds:"+LOCK_TTL_SECONDS+"}") private long lockSeconds = LOCK_TTL_SECONDS;
    @Value("${llmall.shop.cache.lock-wait-millis:"+LOCK_WAIT_MILLIS+"}") private long waitMillis = LOCK_WAIT_MILLIS;
    @Value("${llmall.shop.cache.lock-wait-interval-millis:"+LOCK_WAIT_INTERVAL_MILLIS+"}") private long pollMillis = LOCK_WAIT_INTERVAL_MILLIS;

    public ShopDetailCache(StringRedisTemplate redis, ObjectMapper json,
                          @Qualifier("cacheRebuildExecutor") Executor rebuilds, ShopCacheFallback fallback) {
        this.redis = redis;
        this.json = json;
        this.rebuilds = rebuilds;
        this.fallback = fallback;
    }

    public ShopDTO get(Long id, Supplier<ShopDTO> source) {
        if (id == null || id <= 0) {
            throw new BadRequestException("门店 id 必须为正数");
        }
        try {
            String raw = read(SHOP_CACHE_KEY + id);
            if (raw != null) {
                RedisData<ShopDTO> cached = parse(raw, id);
                if (cached == null) {
                    discard(id, raw);
                } else {
                    if (!cached.getExpireTime().isAfter(LocalDateTime.now())) {
                        refresh(id, source);
                    }
                    return cached.getData();
                }
            }
            return cold(id, source);
        } catch (CacheUnavailable e) {
            warn("read", id, e.getCause());
            return fallback.load(id, source);
        }
    }

    private void refresh(Long id, Supplier<ShopDTO> source) {
        String token;
        try {
            token = lock(id);
        } catch (CacheUnavailable e) {
            warn("refresh-lock", id, e.getCause());
            return;
        }
        if (token == null) {
            return;
        }
        try {
            rebuilds.execute(() -> {
                try {
                    // Queue residence can outlast the lease: don't query the database for a dead task.
                    if (token.equals(read(SHOP_LOCK_KEY + id))) {
                        rebuild(id, token, source, RebuildReason.LOGICAL_EXPIRE);
                    }
                } catch (Exception e) {
                    warn("refresh", id, e);
                } finally {
                    unlock(id, token);
                }
            });
        } catch (RuntimeException e) {
            unlock(id, token);
            warn("rejected", id, e);
        }
    }

    private ShopDTO cold(Long id, Supplier<ShopDTO> source) {
        String token = lock(id);
        if (token != null) {
            try {
                String raw = read(SHOP_CACHE_KEY + id);
                if (raw != null) {
                    RedisData<ShopDTO> cached = parse(raw, id);
                    if (cached != null) {
                        return cached.getData();
                    }
                    discard(id, raw);
                }
                return rebuild(id, token, source, RebuildReason.COLD_MISS);
            } finally {
                unlock(id, token);
            }
        }
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(waitMillis)) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String raw = read(SHOP_CACHE_KEY + id);
            if (raw != null) {
                RedisData<ShopDTO> cached = parse(raw, id);
                if (cached != null) {
                    return cached.getData();
                }
                discard(id, raw);
                break;
            }
        }
        // A distributed owner may still be loading. Fallback is bounded and never publishes.
        return fallback.load(id, source);
    }

    private ShopDTO rebuild(Long id, String token, Supplier<ShopDTO> source, RebuildReason reason) {
        ShopDTO dto = source.get();
        long logical = dto == null ? nullMinutes + ThreadLocalRandom.current().nextLong(nullJitter + 1)
                : ttlMinutes + ThreadLocalRandom.current().nextLong(jitterMinutes + 1);
        long physical = dto == null ? logical : logical + extraMinutes;
        try {
            String payload = json.writeValueAsString(RedisData.of(dto, LocalDateTime.now().plusMinutes(logical)));
            Long published = redis.execute(PUBLISH, keys(id), token, payload,
                    String.valueOf(Duration.ofMinutes(physical).toMillis()));
            if (!Long.valueOf(1).equals(published)) {
                log.debug("门店缓存回填权限已失效: id={}, reason={}", id, reason);
            }
        } catch (Exception e) {
            warn("publish", id, e);
        }
        return dto;
    }

    /** Invoke after commit for writes. No lock wait: atomically revoke old writers and delete the value. */
    public void invalidate(Long id) {
        if (id == null || id <= 0) {
            return;
        }
        try {
            redis.execute(INVALIDATE, keys(id));
        } catch (Exception e) {
            warn("invalidate", id, e);
        }
    }

    private String read(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            throw new CacheUnavailable(e);
        }
    }
    private String lock(Long id) {
        String token = UUID.randomUUID().toString();
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    SHOP_LOCK_KEY + id, token, Duration.ofSeconds(lockSeconds))) ? token : null;
        } catch (RuntimeException e) {
            throw new CacheUnavailable(e);
        }
    }
    private void unlock(Long id, String token) {
        try {
            redis.execute(REMOVE_IF_MATCHES, Collections.singletonList(SHOP_LOCK_KEY + id), token);
        } catch (Exception e) {
            warn("unlock", id, e);
        }
    }
    private void discard(Long id, String raw) {
        try {
            redis.execute(REMOVE_IF_MATCHES, Collections.singletonList(SHOP_CACHE_KEY + id), raw);
        } catch (RuntimeException e) {
            throw new CacheUnavailable(e);
        }
    }
    private RedisData<ShopDTO> parse(String raw, Long id) {
        try {
            RedisData<ShopDTO> value = json.readValue(raw, new TypeReference<RedisData<ShopDTO>>() {});
            if (value == null || value.getExpireTime() == null
                    || (value.getData() != null && !id.equals(value.getData().getId()))) {
                return null;
            }
            return value;
        } catch (Exception e) {
            warn("payload", id, e);
            return null;
        }
    }
    private void warn(String stage, Long id, Throwable error) {
        long now = System.currentTimeMillis();
        long last = lastWarning.get();
        if (now - last >= 10_000 && lastWarning.compareAndSet(last, now)) {
            log.warn("门店缓存降级（同实例日志限频10秒）: stage={}, id={}, failure={}",
                    stage, id, error.getClass().getSimpleName());
        }
    }
    private List<String> keys(Long id) {
        return Arrays.asList(SHOP_LOCK_KEY + id, SHOP_CACHE_KEY + id);
    }
    private static DefaultRedisScript<Long> script(String resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }
    private static class CacheUnavailable extends RuntimeException {
        CacheUnavailable(RuntimeException cause) {
            super(cause);
        }
    }
}
