package com.llmall.common.utils;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 跨服务复用的 Redis Lua 脚本。
 *
 * 【注意】"读出来判断、再按判断结果删除"在 Java 侧必须写成两条命令，两条命令之间存在时间窗，
 * 窗口内 key 可能已被别人改掉，于是"判断时它是 A、动手时它是 B"，判断形同虚设 —— 这类逻辑必须原子。
 *
 * 使用前提：脚本通过 {@code StringRedisTemplate.execute(script, keys, args...)} 执行，键值都是字符串，
 * 因此 {@code DefaultRedisScript} 的结果类型必须与脚本返回值的实际形态一致，否则反序列化会报错。
 */
public final class RedisScripts {

    private RedisScripts() {
    }

    /**
     * 原子"比较并删除"：当前值等于期望值才删除，返回是否真的删掉了。
     *
     * 用途（值都是"本次调用的唯一凭证"，比对成功即代表还没被别人动过）：释放分布式锁（防误删自己超时后
     * 别人重新拿到的那把）、refresh token 轮换（防同一个 token 被并发刷新两次）、短信验证码核销（防重放）、
     * 清理损坏缓存（防删掉刚被别人重建成好值的那份）。
     *
     * 【注意】不能用 {@code RedisTemplate.delete(key)} 代替：DELETE 只看 key 不看值，在上述场景里
     * 都会退化成无条件删除，正是每个场景要防的那件事。
     */
    public static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
}
