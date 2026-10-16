package com.llmall.shop.domain.bo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 带"逻辑过期时间"的缓存包装。
 *
 * 两层过期并存：expireTime 是逻辑过期（由本字段表达），物理 TTL 由写入方在写入时另设
 * （见 ShopServiceImpl：正常数据取"逻辑过期时长 + 额外余量"，空值哨兵取逻辑过期时长本身），
 * 物理到期后 key 连同数据一起消失，所以"永不过期"并不成立。
 *
 * 逻辑过期时先返回旧数据保证可用性，再由拿到互斥锁的线程异步重建缓存。若只靠普通 TTL
 * （物理过期即失效），热点门店缓存物理过期的瞬间，并发请求会同时发现未命中而全部去查库（缓存击穿）。
 * 取舍是牺牲一点实时性，换取任何时刻都能立刻返回。
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
