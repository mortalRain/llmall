package com.llmall.content.constant;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 内容域的 Redis Lua 脚本：把"写成员 + 设 TTL"合成一次往返，消除"写了成员却没设 TTL"的中间态。
 *
 * 【注意】Redis 没有带 TTL 的 SADD/ZADD，两条命令之间的失败窗口（连接抖动、命令超时、进程中途退出）
 * 会让这个 key 永久没有 TTL；现役 Redis 是 {@code maxmemory 0} + {@code noeviction}，无 TTL 的 key
 * 既不过期也不被淘汰。而 {@code follow:user:{userId}}、{@code blog:liked:{blogId}} 这类 key 随
 * 用户数与笔记数无界增长，取关/注销留下的空壳 key 也不会再有写入来续期。
 * 代价是失去 pipeline 的批量能力，但这里每次操作本来就是单成员语义。
 */
public final class ContentRedisScripts {

    private ContentRedisScripts() {
    }

    /**
     * SADD + PEXPIRE（同一次往返）。KEYS[1]=集合 key；ARGV[1]=成员；ARGV[2]=TTL（毫秒）。
     * 返回 1 表示成员是新加入的（"是否已关注"的判据），0 表示本来就在集合里。
     */
    public static final DefaultRedisScript<Long> SET_ADD_WITH_TTL = new DefaultRedisScript<>(
            "local added = redis.call('sadd', KEYS[1], ARGV[1]) "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]) "
                    + "return added",
            Long.class);

    /**
     * SREM + PEXPIRE（同一次往返）。KEYS[1]=集合 key；ARGV[1]=成员；ARGV[2]=TTL（毫秒）。
     * 集合被删空时 {@code pexpire} 对不存在的 key 是空操作，不会把 key 复活。
     */
    public static final DefaultRedisScript<Long> SET_REMOVE_WITH_TTL = new DefaultRedisScript<>(
            "local removed = redis.call('srem', KEYS[1], ARGV[1]) "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]) "
                    + "return removed",
            Long.class);

    /**
     * ZADD + PEXPIRE（同一次往返），用于"点赞排行榜"。KEYS[1]=ZSet key；ARGV[1]=成员(userId)；
     * ARGV[2]=分数(点赞时间戳)；ARGV[3]=TTL（毫秒）。score 用时间戳而不是序号，
     * 是为了 {@code reverseRange} 直接得到"最近点赞的人"的顺序。
     */
    public static final DefaultRedisScript<Long> ZSET_ADD_WITH_TTL = new DefaultRedisScript<>(
            "local added = redis.call('zadd', KEYS[1], ARGV[2], ARGV[1]) "
                    + "redis.call('pexpire', KEYS[1], ARGV[3]) "
                    + "return added",
            Long.class);

    /**
     * ZREM + PEXPIRE（同一次往返）。KEYS[1]=ZSet key；ARGV[1]=成员；ARGV[2]=TTL（毫秒）。
     * 续期是为了让"还在被使用的笔记"一直有排行榜，而不是为了延长被删空的 key。
     */
    public static final DefaultRedisScript<Long> ZSET_REMOVE_WITH_TTL = new DefaultRedisScript<>(
            "local removed = redis.call('zrem', KEYS[1], ARGV[1]) "
                    + "redis.call('pexpire', KEYS[1], ARGV[2]) "
                    + "return removed",
            Long.class);

    /**
     * 批量重建集合缓存：DEL + SADD 全部成员 + PEXPIRE，必须在同一次往返里完成。
     * 【注意】"DEL → 逐条 SADD → EXPIRE"中途失败会留下"部分视图"的集合，调用方（共同关注、
     * 粉丝数可信度校验）会当成完整数据用，得到偏少但不报错的结果；一次往返至少保证
     * "要么整份写进去，要么压根没写"。
     * KEYS[1]=集合 key；ARGV[1]=TTL（毫秒）；ARGV[2..]=全部成员。
     */
    public static final DefaultRedisScript<Long> SET_REBUILD_WITH_TTL = new DefaultRedisScript<>(
            "redis.call('del', KEYS[1]) "
                    + "for i = 2, #ARGV do redis.call('sadd', KEYS[1], ARGV[i]) end "
                    + "redis.call('pexpire', KEYS[1], ARGV[1]) "
                    + "return #ARGV - 1",
            Long.class);

    /**
     * 批量重建 ZSet（点赞排行榜）：DEL + ZADD 全部成员 + PEXPIRE，一次往返。
     * KEYS[1]=ZSet key；ARGV[1]=TTL（毫秒）；其后为成对的 member, score。
     * 与 SET_REBUILD_WITH_TTL 同理，一次往返避免留下部分视图。
     */
    public static final DefaultRedisScript<Long> ZSET_REBUILD_WITH_TTL = new DefaultRedisScript<>(
            "redis.call('del', KEYS[1]) "
                    + "local n = 0 "
                    + "for i = 2, #ARGV, 2 do "
                    + "  redis.call('zadd', KEYS[1], ARGV[i + 1], ARGV[i]) "
                    + "  n = n + 1 "
                    + "end "
                    + "if n > 0 then redis.call('pexpire', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);
}
