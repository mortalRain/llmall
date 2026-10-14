package com.llmall.shop.domain.bo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 带"逻辑过期时间"的缓存包装。
 *
 * 物理上永不过期（或很长），过期只体现在数据里的 expireTime：发现逻辑过期时先返回旧数据
 * 保证可用性，再由拿到互斥锁的线程异步重建缓存。若改用普通 TTL，热点门店缓存物理过期的
 * 瞬间，并发请求会同时发现未命中而全部去查库（缓存击穿）。取舍是牺牲一点实时性，
 * 换取任何时刻都能立刻返回。
 */
@Data
public class RedisData<T> {

    /** 逻辑过期时间 */
    private LocalDateTime expireTime;

    /** 业务数据（可能为 null，表示"数据库里也没有"，即空值缓存） */
    private T data;

    public static <T> RedisData<T> of(T data, LocalDateTime expireTime) {
        RedisData<T> redisData = new RedisData<>();
        redisData.setData(data);
        redisData.setExpireTime(expireTime);
        return redisData;
    }
}
