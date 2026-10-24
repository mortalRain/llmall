package com.llmall.shop.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.CollUtils;
import com.llmall.shop.domain.bo.RebuildReason;
import com.llmall.shop.domain.bo.RedisData;
import com.llmall.shop.domain.po.ConsumeRecord;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.event.ShopDocChangedEvent;
import com.llmall.shop.mapper.ConsumeRecordMapper;
import com.llmall.shop.mapper.ShopMapper;
import com.llmall.shop.mapper.ShopTypeMapper;
import com.llmall.shop.mq.ShopEventPublisher;
import com.llmall.shop.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.llmall.shop.constant.ShopRedisConstants.*;

/**
 * 门店域：详情缓存的读路径与派生数据维护 —— 空值哨兵防穿透、逻辑过期 + 互斥锁异步重建防击穿、
 * TTL 随机抖动防雪崩，另含热度分与热度榜、GEO 附近门店、HyperLogLog UV。
 *
 * 【注意】详情缓存、热度榜、GEO 都是可重建的推导数据，写入失败只降级记日志，不得影响主链路。
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** 营业中 */
    private static final int STATUS_OPEN = 1;

    /**
     * 释放锁的 Lua 脚本：只有锁的值等于自己写入的 token 才删，返回是否真删掉。
     *
     * 【注意】比对 + 删除必须原子：写成 Java 先 get 再 delete 时，两次调用之间自己的锁可能已超时
     * 并被别人持有，DEL 删掉的就是别人的锁，互斥失效。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 访问计数脚本：PFADD（UV，HyperLogLog）+ INCR（PV）+ 首次 PEXPIRE，一次往返完成。
     *
     * 【注意】拆成多条命令时，后续命令失败或进程在中途退出，这个 key 就永久没有 TTL；
     * 而 {@code uv:shop:{日期}:{门店id}} 与 {@code pv:shop:{日期}:{门店id}} 都是按天新建 key 的，
     * 无 TTL 会按"天数 × 门店数"累积
     * （现役 Redis 为 {@code maxmemory 0} + {@code noeviction}，既不淘汰也不过期）。
     * 【注意】{@code PEXPIRE ... NX} 只在没有 TTL 时设置：否则每次访问都续期，活跃 key 等于永不过期。
     * 返回值沿用 UV key 的 PEXPIRE 结果，供调用方判断是否首次创建。
     */
    private static final DefaultRedisScript<Long> VISIT_UV_SCRIPT = new DefaultRedisScript<>(
            "redis.call('pfadd', KEYS[1], ARGV[1]) "
                    + "local ttl = redis.call('pexpire', KEYS[1], ARGV[2], 'NX') "
                    + "redis.call('incr', KEYS[2]) "
                    + "redis.call('pexpire', KEYS[2], ARGV[2], 'NX') "
                    + "return ttl",
            Long.class);

    /**
     * 清除损坏缓存的 CAS 脚本：只有值仍等于我读到的那份脏值才删。
     *
     * 【注意】无条件 DEL 会在"我读脏值 → 别人重建写入好值 → 我 DEL"的交错里删掉刚写好的好数据。
     */
    private static final DefaultRedisScript<Long> DISCARD_IF_UNCHANGED_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** 本机节流：同一门店的榜单成员写入最多每 RANK_WRITE_MIN_INTERVAL_MINUTES 一次（值 = 上次写入时刻） */
    private final Map<Long, Long> rankWriteStamps = new ConcurrentHashMap<>();

    /** 本机节流：同一 id 的空值哨兵写入最多每 NULL_SENTINEL_MERGE_MILLIS 一次（值 = 上次写入时刻） */
    private final Map<Long, Long> nullSentinelStamps = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor cacheRebuildExecutor;
    private final ConsumeRecordMapper consumeRecordMapper;
    private final ShopTypeMapper shopTypeMapper;

    /** 正常数据的逻辑过期时长（分钟）：默认 30，可用 llmall.shop.cache.ttl-minutes 覆盖 */
    @Value("${llmall.shop.cache.ttl-minutes:" + CACHE_TTL_MINUTES + "}")
    private long cacheTtlMinutes;

    /** 正常数据 TTL 的随机抖动上限（分钟）：防雪崩 */
    @Value("${llmall.shop.cache.ttl-jitter-minutes:" + CACHE_TTL_JITTER_MINUTES + "}")
    private long cacheTtlJitterMinutes;

    /** 物理 TTL 额外时长（分钟）：留给"逻辑过期后返回旧值 + 异步重建"的窗口 */
    @Value("${llmall.shop.cache.physical-extra-minutes:" + CACHE_PHYSICAL_EXTRA_MINUTES + "}")
    private long physicalExtraMinutes;

    /** 空值哨兵的逻辑过期时长（分钟）：默认 2 */
    @Value("${llmall.shop.cache.null-ttl-minutes:" + NULL_CACHE_TTL_MINUTES + "}")
    private long nullCacheTtlMinutes;

    /** 空值哨兵 TTL 的随机抖动上限（分钟） */
    @Value("${llmall.shop.cache.null-ttl-jitter-minutes:" + NULL_CACHE_TTL_JITTER_MINUTES + "}")
    private long nullCacheTtlJitterMinutes;

    /** 重建锁时长（秒）：必须大于一次重建耗时，否则会出现"锁过期→两个线程同时重建" */
    @Value("${llmall.shop.cache.lock-ttl-seconds:" + LOCK_TTL_SECONDS + "}")
    private long lockTtlSeconds;

    /** 未命中路径没抢到锁时的最长等待（毫秒） */
    @Value("${llmall.shop.cache.lock-wait-millis:" + LOCK_WAIT_MILLIS + "}")
    private long lockWaitMillis;

    /** 未命中路径没抢到锁时的轮询间隔（毫秒） */
    @Value("${llmall.shop.cache.lock-wait-interval-millis:" + LOCK_WAIT_INTERVAL_MILLIS + "}")
    private long lockWaitIntervalMillis;

    /** 榜单 ZSet / GEO 索引的 TTL（分钟），每次写入滑动续期；0 = 不设 TTL（不推荐，只增不减的推导数据会永久驻留） */
    @Value("${llmall.shop.cache.rank-ttl-minutes:" + RANK_TTL_MINUTES + "}")
    private long rankTtlMinutes;

    /** 同一门店两次榜单成员写入的最小间隔（毫秒）；0 = 不节流（回归测试会用 0 拿到确定性） */
    @Value("${llmall.shop.cache.rank-write-min-interval-millis:" + (RANK_WRITE_MIN_INTERVAL_MINUTES * 60_000L) + "}")
    private long rankWriteMinIntervalMillis;

    /**
     * UV 计数器保留天数：{@code uv:shop:{日期}:{门店id}} 是按天新建 key 的，本值直接决定 Redis 里
     * 同时存在多少个 UV key（≈ 保留天数 × 被访问过的门店数），是内存治理的旋钮，需可不重编译地收紧。
     */
    @Value("${llmall.shop.cache.uv-ttl-days:" + UV_TTL_DAYS + "}")
    private long uvTtlDays;

    /** 门店类型名缓存（10 条静态字典，进程内缓存即可，避免每次查库） */
    private volatile java.util.Map<Long, String> typeNameCache;

    /** 热度事件必须让搜索域感知，但不能在事务里直发 MQ：统一交 ShopEventPublisher 走"同事务登记本地消息表 + 提交后投递" */
    private final ShopEventPublisher eventPublisher;

    /**
     * 显式构造器：Executor Bean 有多个（Boot 还会自动装配 applicationTaskExecutor），必须用 @Qualifier 指定，
     * 而 Lombok 不会把字段上的 @Qualifier 拷贝到构造参数上。
     */
    public ShopServiceImpl(StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor,
                           ConsumeRecordMapper consumeRecordMapper,
                           ShopTypeMapper shopTypeMapper,
                           ShopEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.consumeRecordMapper = consumeRecordMapper;
        this.shopTypeMapper = shopTypeMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ShopDTO queryById(Long id) {
        if (id == null) {
            throw new BadRequestException("门店 id 不能为空");
        }
        String cacheKey = SHOP_CACHE_KEY + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);

        // 空值哨兵也算命中：它的 data 为 null，返回 null 即表示"库里也没有"
        if (cached != null) {
            RedisData<ShopDTO> redisData = parseCache(cached);
            if (redisData == null) {
                // 脏值当作没缓存：CAS 删掉后回源 DB（脏值不会自愈，不能让它把最热读接口打成 500）
                discardCorruptedCache(cacheKey, cached);
                return rebuildCacheWithMutex(id);
            }
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                return redisData.getData();
            }
            // 逻辑过期：先返回旧数据保证可用性，再异步重建保证最终一致
            String lockKey = SHOP_LOCK_KEY + id;
            String token = tryLock(lockKey);
            if (token != null) {
                cacheRebuildExecutor.execute(() -> {
                    try {
                        log.info("异步重建门店缓存, id={}", id);
                        // 逻辑过期路径：必须回填，否则缓存越过逻辑过期后再也不会刷新
                        rebuildCache(id, RebuildReason.LOGICAL_EXPIRE);
                    } catch (Exception e) {
                        log.error("重建门店缓存失败, id={}", id, e);
                    } finally {
                        // 必须在 finally 释放：重建抛异常时漏掉释放，该门店此后无法再重建
                        unlock(lockKey, token);
                    }
                });
            }
            return redisData.getData();
        }

        // 未命中（冷 key，或空值哨兵已过期）走互斥回源，避免同一 id 的并发请求一起打 DB
        return rebuildCacheWithMutex(id);
    }

    /**
     * 缓存未命中路径的互斥回源（防缓存击穿）：与逻辑过期路径共用同一把 {@code lock:shop:{id}}，
     * 一个冷门店被 N 个并发请求同时打时 DB 只挨 1 次查询。
     *
     * 【注意】抢不到锁的请求不发 DB 查询，而是短轮询等持锁线程把结果（数据或空值哨兵）写进缓存，
     * 超时则兜底自己回源一次且不回填 —— 写缓存是持锁线程的职责，两个线程都写会互相覆盖。
     */
    private ShopDTO rebuildCacheWithMutex(Long id) {
        String cacheKey = SHOP_CACHE_KEY + id;
        String lockKey = SHOP_LOCK_KEY + id;
        String token = tryLock(lockKey);
        if (token != null) {
            try {
                // 二次检查：抢到锁之前可能已有人重建完并释放了锁，无需再查 DB。
                // 【注意】必须把原始 value 一起传给 rebuildCache：它就是"查库前读到过缓存"的证据，
                // 用来判断缓存是否刚被别人删掉（见 rebuildCache）。
                String recheck = redisTemplate.opsForValue().get(cacheKey);
                RedisData<ShopDTO> hit = null;
                if (recheck != null) {
                    hit = parseCache(recheck);
                    if (hit == null) {
                        discardCorruptedCache(cacheKey, recheck);
                        recheck = null;
                    }
                }
                if (recheck != null) {
                    return hit.getData();
                }
                return rebuildCache(id, RebuildReason.COLD_MISS);
            } finally {
                unlock(lockKey, token);
            }
        }
        long deadline = System.currentTimeMillis() + lockWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(lockWaitIntervalMillis);
            String again = redisTemplate.opsForValue().get(cacheKey);
            if (again != null) {
                RedisData<ShopDTO> hit = parseCache(again);
                if (hit != null) {
                    return hit.getData();
                }
                // 脏值不能在轮询里反复解析：清掉并停止等待，交给下面的兜底回源
                discardCorruptedCache(cacheKey, again);
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        log.warn("等待门店缓存重建超时，降级为直接回源 DB（不回填缓存）, id={}", id);
        Shop shop = getById(id);
        return shop == null ? null : toDTO(shop);
    }

    /** 短睡眠等待重建结果；被中断则恢复中断标记后退出（吞掉中断会让优雅停机失效） */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 查库并回填缓存；门店不存在时写入空值哨兵。两种 key 都必须带 Redis 物理 TTL。
     *
     * @param reason 重建原因，见 {@link RebuildReason}。
     *               【注意】只有 EVICT_RACE 才放弃回填：此时查到的可能是删除前读到的旧值。
     *               逻辑过期路径必须回填，否则缓存越过逻辑过期后永远不会刷新。
     */
    private ShopDTO rebuildCache(Long id, RebuildReason reason) {
        Shop shop = getById(id);
        ShopDTO dto = shop == null ? null : toDTO(shop);
        if (reason == RebuildReason.EVICT_RACE) {
            log.warn("缓存重建期间缓存被并发删除，本次只回源不回填，避免把旧值写回缓存, id={}", id);
            return dto;
        }
        if (shop == null) {
            writeNullSentinel(id);
            return null;
        }
        long logicalMinutes = cacheTtlMinutes + ThreadLocalRandom.current().nextLong(cacheTtlJitterMinutes + 1);
        long physicalMinutes = logicalMinutes + physicalExtraMinutes;
        RedisData<ShopDTO> redisData = RedisData.of(dto, LocalDateTime.now().plusMinutes(logicalMinutes));
        try {
            // 【注意】必须带物理 TTL：不带 TTL 时匿名刷 GET /shops/{随机id}（含空值哨兵）会让
            // 这些 key 永久驻留，内存单调增长，最终把真正有用的业务缓存挤掉。
            redisTemplate.opsForValue().set(SHOP_CACHE_KEY + id,
                    objectMapper.writeValueAsString(redisData),
                    Duration.ofMinutes(physicalMinutes));
        } catch (Exception e) {
            // 写缓存失败不能影响主流程：降级为直接返回 DB 数据
            log.error("写入门店缓存失败, id={}", id, e);
        }
        return dto;
    }

    /**
     * 空值哨兵的边界节流：哨兵自身有 TTL（默认 2~3 分钟），但"哨兵刚过期 + 同一个不存在 id 的高并发"
     * 仍会让 N 个请求一起回源 DB；这里用进程内时间戳把同一 id 的哨兵写入收敛为每窗口最多一次，
     * 未写哨兵的请求照旧返回 null（语义不变，都是"不存在"）。
     *
     * 【注意】用本机 Map 而非 Redis 锁：这条路已是"DB 查不到"的低频兜底，再加一次 Redis 往返不划算。
     */
    private void writeNullSentinel(Long id) {
        long now = System.currentTimeMillis();
        Long last = nullSentinelStamps.get(id);
        if (last != null && now - last < NULL_SENTINEL_MERGE_MILLIS) {
            return;
        }
        nullSentinelStamps.put(id, now);
        if (nullSentinelStamps.size() > 10000) {
            // 无界 Map 本身就是内存泄漏：窗口已过的条目直接清掉（低频操作，全量清理足够）
            nullSentinelStamps.entrySet().removeIf(e -> now - e.getValue() > NULL_SENTINEL_MERGE_MILLIS);
        }
        long logicalMinutes = nullCacheTtlMinutes + ThreadLocalRandom.current().nextLong(nullCacheTtlJitterMinutes + 1);
        RedisData<ShopDTO> redisData = RedisData.of(null, LocalDateTime.now().plusMinutes(logicalMinutes));
        try {
            redisTemplate.opsForValue().set(SHOP_CACHE_KEY + id,
                    objectMapper.writeValueAsString(redisData),
                    Duration.ofMinutes(logicalMinutes));
        } catch (Exception e) {
            log.error("写入空值哨兵失败, id={}", id, e);
        }
    }

    /**
     * 缓存 JSON → RedisData；解析失败返回 {@code null}，由调用方删除脏 key 后回源 DB。
     *
     * 【注意】不能在这里抛异常：一条写脏或格式变更后残留的 value 会让 {@code GET /shops/{id}}
     * 这个最热的读接口持续 500，且脏值不会自愈。缓存是可选加速层，损坏不应升级成业务故障。
     */
    private RedisData<ShopDTO> parseCache(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<RedisData<ShopDTO>>() {
            });
        } catch (Exception e) {
            log.error("门店缓存数据解析失败，删除脏 key 并回源 DB。value 前 200 字符: {}",
                    StrUtil.maxLength(json, 200), e);
            return null;
        }
    }

    /**
     * 清掉一个已知损坏的缓存 key，用 Lua 做 CAS（值仍等于我读到的那份脏值才删）。
     *
     * 【注意】不能直接 delete：A 读到脏值 → B（持锁线程）重建写入好值 → A 才删除，会删掉好数据。
     */
    private void discardCorruptedCache(String cacheKey, String corruptedValue) {
        try {
            Long deleted = redisTemplate.execute(DISCARD_IF_UNCHANGED_SCRIPT,
                    Collections.singletonList(cacheKey), corruptedValue);
            log.warn("已清除损坏的门店缓存, key={}, 是否删除={}", cacheKey, deleted);
        } catch (Exception e) {
            // 删不掉不影响本次请求（本次已经回源 DB）；脏 key 会随物理 TTL 自然消失
            log.warn("清除损坏的门店缓存失败, key={}", cacheKey, e);
        }
    }

    /**
     * 加锁：value 用本次调用生成的 UUID，返回该 token（没抢到返回 null）。
     *
     * 【注意】不能用固定值：锁里没有"所有者"信息就只能无条件 DEL，自己的锁已超时（重建比
     * lockTtlSeconds 还慢）并被别人重新持有后，收尾的 DEL 会删掉新持有者的锁，互斥被击穿。
     */
    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, token, Duration.ofSeconds(lockTtlSeconds));
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /**
     * 解锁：在 Redis 侧原子地"比对 value + 删除"（Lua），只有自己加的锁才删得掉（被别人拿走时返回 false）
     */
    private boolean unlock(String key, String token) {
        if (token == null) {
            return false;
        }
        try {
            Long deleted = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
            return deleted != null && deleted > 0;
        } catch (Exception e) {
            // 释放失败不能把异常带到业务上：锁本身有 TTL，最多 lockTtlSeconds 后自动过期
            log.warn("释放门店缓存锁失败, key={}", key, e);
            return false;
        }
    }

    @Override
    public List<ShopDTO> queryByIds(Collection<Long> ids) {
        if (CollUtils.isEmpty(ids)) {
            return CollUtils.emptyList();
        }
        // 必须统一走 toDTO：单位换算（人均元→分）与类型名回填只在这一处做，否则详情与列表口径会不一致
        return listByIds(ids).stream().map(this::toDTO).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public PageDTO<ShopDTO> pageQuery(Long typeId, String keyword, Integer page, Integer size) {
        // 【注意】本项目 MyBatis-Plus 为 3.4.2，Page.of(...) 是 3.5+ 才有的静态工厂，这里只能用构造器
        // 分页上下限统一收敛到 PageQuery：就地 if-else 容易只挡一侧，size = 100000 会原样落进 SQL
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10);
        Page<Shop> result = lambdaQuery()
                .eq(typeId != null, Shop::getTypeId, typeId)
                .eq(Shop::getStatus, STATUS_OPEN)
                .like(StrUtil.isNotBlank(keyword), Shop::getName, keyword)
                // 按热度分排序：热度由评价事件异步更新
                .orderByDesc(Shop::getHotScore)
                .orderByDesc(Shop::getComments)
                .page(new Page<>(current, pageSize));
        // 【注意】必须显式走 toDTO：PageDTO.of(page, ShopDTO.class) 会绕过单位换算与类型名回填，
        // 导致列表接口人均还是"元"、typeName 为空
        List<ShopDTO> list = result.getRecords().stream().map(this::toDTO).collect(java.util.stream.Collectors.toList());
        return PageDTO.of(result, list);
    }

    /**
     * 热度榜单（首页入口）：只返回营业中的门店，并把失格成员从 ZSet 里剪掉。
     *
     * 【注意】顺序必须按 ZSet 榜单 id 还原（DB 的 IN 查询不保证顺序），排序以 ZSet 分数为准。
     * 【注意】剪枝不可省：ZSet 没有单成员 TTL，"已删除/已打烊下线"的成员会永久占着候选名次。
     */
    @Override
    public List<ShopDTO> queryHotList(Long typeId, Integer limit) {
        int size = (limit == null || limit <= 0) ? 10 : Math.min(limit, 100);
        String rankKey = typeId == null ? SHOP_HOT_RANK_ALL : SHOP_HOT_RANK_KEY + typeId;

        // 多取候选：剪掉失格成员后仍要凑够 size 条，上限取 size 的 3 倍且不超过 300
        int candidateSize = Math.min(size * 3, 300);
        Set<String> idSet = redisTemplate.opsForZSet().reverseRange(rankKey, 0, candidateSize - 1);
        if (CollUtils.isEmpty(idSet)) {
            // 榜单未预热：回源 DB 并顺手预热，避免首次访问空白
            List<Shop> shops = lambdaQuery()
                    .eq(typeId != null, Shop::getTypeId, typeId)
                    .eq(Shop::getStatus, STATUS_OPEN)
                    .orderByDesc(Shop::getHotScore)
                    .last("LIMIT " + size)
                    .list();
            shops.forEach(this::addToRank);
            return shops.stream().map(this::toDTO).collect(java.util.stream.Collectors.toList());
        }

        List<Long> ids = idSet.stream().map(Long::valueOf).collect(Collectors.toList());
        Map<Long, Shop> shopMap = listByIds(ids).stream()
                .collect(Collectors.toMap(Shop::getId, s -> s, (a, b) -> a));
        List<ShopDTO> result = new ArrayList<>(size);
        List<String> disqualified = new ArrayList<>();
        for (Long id : ids) {
            Shop shop = shopMap.get(id);
            // 查不到行（门店已删）或已非营业中（打烊/下线）→ 失格：不进结果，并从榜上剪掉
            if (shop == null || !Integer.valueOf(STATUS_OPEN).equals(shop.getStatus())) {
                disqualified.add(String.valueOf(id));
                continue;
            }
            if (result.size() < size) {
                result.add(toDTO(shop));
            }
        }
        if (!disqualified.isEmpty()) {
            try {
                redisTemplate.opsForZSet().remove(rankKey, disqualified.toArray());
                log.info("热度榜剪枝：移除 {} 个已下线/已删除的门店, rankKey={}", disqualified.size(), rankKey);
            } catch (Exception e) {
                // 剪枝失败不影响本次返回（结果里已滤掉），下次查询会再试
                log.warn("热度榜剪枝失败, rankKey={}", rankKey, e);
            }
        }
        return result;
    }

    @Override
    public List<ShopDTO> nearby(Double x, Double y, Double radiusKm, Integer limit) {
        if (x == null || y == null) {
            throw new BadRequestException("经纬度不能为空");
        }
        double radius = (radiusKm == null || radiusKm <= 0) ? 5D : radiusKm;
        int size = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoRadius(x, y, radius, size);
        if (results == null || CollUtils.isEmpty(results.getContent())) {
            // GEO 索引未预热（首次部署、Redis 重启丢数据）时回源 DB 重建一次再查，与 queryHotList 的回源预热一致
            Long geoSize = redisTemplate.opsForZSet().zCard(SHOP_GEO_KEY);
            if (geoSize == null || geoSize == 0L) {
                log.info("GEO 索引为空，按 DB 坐标重建后重试查询");
                if (rebuildGeoIndex() > 0) {
                    results = geoRadius(x, y, radius, size);
                }
            }
        }
        if (results == null || CollUtils.isEmpty(results.getContent())) {
            return CollUtils.emptyList();
        }

        List<Long> ids = new ArrayList<>();
        Map<Long, Double> distanceMap = new HashMap<>();
        results.forEach(geoResult -> {
            Long shopId = Long.valueOf(geoResult.getContent().getName());
            ids.add(shopId);
            if (geoResult.getDistance() != null) {
                distanceMap.put(shopId, geoResult.getDistance().getValue());
            }
        });

        Map<Long, Shop> shopMap = listByIds(ids).stream()
                .collect(Collectors.toMap(Shop::getId, s -> s, (a, b) -> a));
        List<ShopDTO> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Shop shop = shopMap.get(id);
            if (shop == null) {
                continue;
            }
            ShopDTO dto = toDTO(shop);
            dto.setDistance(distanceMap.get(id));
            result.add(dto);
        }
        return result;
    }

    /** GEO 半径查询：抽成独立方法，便于索引惰性重建后复用同一查询条件 */
    private GeoResults<RedisGeoCommands.GeoLocation<String>> geoRadius(Double x, Double y, double radius, int size) {
        return redisTemplate.opsForGeo().radius(
                SHOP_GEO_KEY,
                new Circle(new Point(x, y), new Distance(radius, Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance().sortAscending().limit(size));
    }

    /**
     * 热度分 = base × 时间衰减。
     *   base  = score/10×4（评分权重最高；评分按 ×10 存储，故除以 10）
     *         + log10(comments+1)×3 + log10(sold+1)×3（评价数与销量用 log 抑制刷评/爆款通吃）
     *   decay = 1 / (1 + Δ天/7)：7 天衰减一半
     *
     * 【注意】衰减锚点取"最近一次评价时间"而非重算时间，否则每次重算都会重置衰减，衰减失效。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public double recomputeHotScore(Long shopId) {
        Shop shop = getById(shopId);
        if (shop == null) {
            throw new BadRequestException("门店不存在: " + shopId);
        }
        double scorePart = nz(shop.getScore()) / 10.0 * 4;
        double commentPart = Math.log10(nz(shop.getComments()) + 1) * 3;
        double soldPart = Math.log10(nz(shop.getSold()) + 1) * 3;
        double base = scorePart + commentPart + soldPart;

        LocalDateTime anchor = shop.getLastReviewTime() != null ? shop.getLastReviewTime() : shop.getCreateTime();
        double days = 0D;
        if (anchor != null) {
            days = Math.max(Duration.between(anchor, LocalDateTime.now()).toMinutes() / 1440.0, 0D);
        }
        double decay = 1.0 / (1.0 + days / 7.0);
        double hotScore = BigDecimal.valueOf(base * decay)
                .setScale(4, RoundingMode.HALF_UP).doubleValue();

        lambdaUpdate()
                .eq(Shop::getId, shopId)
                .set(Shop::getHotScore, hotScore)
                .set(Shop::getHotUpdateTime, LocalDateTime.now())
                .update();

        // 写榜单 + 失效详情缓存（下次读取时重建，保证列表与详情一致）
        addToRank(shop.setHotScore(hotScore));
        evictCacheAfterCommit(shopId);

        // 【注意】事件与上面的热度/销量更新处于同一个本地事务（同事务登记 llmall-shop.message_log，
        // 提交后投递，失败由 MessageRetryTask 退避重发、下游漏处理由 ShopSearchReconcileTask 对账补发）。
        // 事件体是整行门店快照（含 sold/status），搜索域可整行 upsert。
        // 放在这里而不是各调用方：改热度的路径（评价、订单支付、运维重算）都必经此处，新增调用方不会漏发。
        eventPublisher.publishHotChanged(buildDocChangedEvent(shop, hotScore));

        log.info("门店热度已重算: shopId={}, base={}, decay={}, hotScore={}",
                shopId, String.format("%.4f", base), String.format("%.4f", decay), hotScore);
        return hotScore;
    }

    /**
     * 用当前事务内读到的 shop 行构造搜索域需要的整行快照。
     *
     * 【注意】两处口径错了不报错、只显示错数字：{@code avgPrice} 的 shop.avg_price 存「元」，而搜索读模型
     * 与前端契约都是「分」（此处乘 100，与 toDTO 同口径）；{@code typeName} 需按 type_id 回字典表补全。
     */
    private ShopDocChangedEvent buildDocChangedEvent(Shop shop, double hotScore) {
        return new ShopDocChangedEvent()
                .setShopId(shop.getId())
                .setTypeId(shop.getTypeId())
                .setTypeName(typeNameOf(shop.getTypeId()))
                .setName(shop.getName())
                .setArea(shop.getArea())
                .setAddress(shop.getAddress())
                .setX(shop.getX())
                .setY(shop.getY())
                // 元 → 分
                .setAvgPrice(shop.getAvgPrice() == null ? null : shop.getAvgPrice() * 100)
                .setSold(shop.getSold())
                .setComments(shop.getComments())
                .setScore(shop.getScore())
                .setHotScore(hotScore)
                .setStatus(shop.getStatus());
    }

    @Override
    public int applyReview(Long shopId, Integer score) {
        if (shopId == null || score == null) {
            return 0;
        }
        // 一条 SQL 完成"读-算-写"，避免并发评价下丢更新（详见 ShopMapper#applyReview 的注释）
        int rows = baseMapper.applyReview(shopId, score);
        // 评分变化影响详情缓存：本方法通常运行在 ReviewCreatedListener 的事务里（占位 + 累加同事务），
        // 所以同样是提交后才失效；无事务上下文时立即删除
        if (rows > 0) {
            evictCacheAfterCommit(shopId);
        }
        return rows;
    }

    @Override
    public int applyOrderSold(Long shopId, Integer num) {
        if (shopId == null) {
            return 0;
        }
        int rows = baseMapper.addSold(shopId, (num == null || num <= 0) ? 1 : num);
        if (rows > 0) {
            // 销量是热度公式的输入之一，必须重算，否则榜单与实际不符
            recomputeHotScore(shopId);
        }
        return rows;
    }

    @Override
    public boolean isOrderConsumed(Long orderId) {
        if (orderId == null) {
            return true;
        }
        // MyBatis-Plus 3.5.x 起 selectCount 返回 Long（3.4.x 是 Integer），类型必须跟着改
        Long count = consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getBizType, MqConstants.BIZ_ORDER_PAID)
                .eq(ConsumeRecord::getBizId, String.valueOf(orderId))
                .eq(ConsumeRecord::getConsumer, MqConstants.CONSUMER_SHOP));
        return count != null && count > 0;
    }

    /**
     * 榜单成员写入：一次 pipeline 写全站榜 + 分类榜，并顺带续期两者的 TTL。
     *
     * 【注意】必须带 TTL 且每次写都续期：{@code hot:shop:{typeId}} 按分类按需新建，门店换分类后
     * 旧榜不会有人再写，无 TTL 就会永久驻留（现役 Redis 为 noeviction，不淘汰）。
     * 【注意】用 pipeline 而非两次独立 add：两个榜单要反映同一个热度分，分开写中间失败会出现
     * "全站榜已更新、分类榜还是旧分"的错位。
     * 【注意】节流是性能优化而非正确性依赖（分数本来就是覆盖写，多实例各自节流不影响结果）。
     */
    private void addToRank(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return;
        }
        if (!rankWriteDue(shopId)) {
            return;
        }
        double hot = shop.getHotScore() == null ? 0D : shop.getHotScore();
        String member = String.valueOf(shopId);
        List<String> rankKeys = new ArrayList<>(2);
        rankKeys.add(SHOP_HOT_RANK_ALL);
        if (shop.getTypeId() != null) {
            rankKeys.add(SHOP_HOT_RANK_KEY + shop.getTypeId());
        }
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(RedisOperations operations) {
                    for (String rankKey : rankKeys) {
                        operations.opsForZSet().add(rankKey, member, hot);
                        if (rankTtlMinutes > 0) {
                            operations.expire(rankKey, Duration.ofMinutes(rankTtlMinutes));
                        }
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            // 榜单是可重建的读模型（读路径发现缺失会回源预热），写失败只记 ERROR，不能带崩"评价 → 热度分落库"主链路
            log.error("写入热度榜失败（读路径会自愈重建）, shopId={}, hotScore={}", shopId, hot, e);
        }
    }

    /** 榜单写入节流判定：窗口内已写过返回 false。顺带把过期条目清出 Map（避免本机 Map 无界增长） */
    private boolean rankWriteDue(Long shopId) {
        long now = System.currentTimeMillis();
        if (rankWriteMinIntervalMillis <= 0) {
            return true;
        }
        Long last = rankWriteStamps.get(shopId);
        if (last != null && now - last < rankWriteMinIntervalMillis) {
            return false;
        }
        rankWriteStamps.put(shopId, now);
        if (rankWriteStamps.size() > 10000) {
            rankWriteStamps.entrySet().removeIf(e -> now - e.getValue() > rankWriteMinIntervalMillis);
        }
        return true;
    }

    /**
     * 把门店从所有榜单里摘掉（下线/删除时调用）。
     *
     * 【注意】必须摘所有分类榜：门店 typeId 可改，只按当前 typeId 摘会在旧分类榜留下僵尸成员
     * （分类字典表只有 10 条，全扫一遍成本极低）。
     */
    private void removeFromRank(Long shopId) {
        if (shopId == null) {
            return;
        }
        String member = String.valueOf(shopId);
        List<String> rankKeys = new ArrayList<>();
        rankKeys.add(SHOP_HOT_RANK_ALL);
        try {
            // 用类型字典表枚举所有分类榜单 key（进程内缓存，代价可忽略）
            for (Long typeId : allTypeIds()) {
                rankKeys.add(SHOP_HOT_RANK_KEY + typeId);
            }
        } catch (Exception e) {
            log.warn("枚举门店类型失败，榜单摘除只覆盖全站榜, shopId={}", shopId, e);
        }
        try {
            redisTemplate.opsForZSet().remove(rankKeys.get(0), member);
            for (int i = 1; i < rankKeys.size(); i++) {
                redisTemplate.opsForZSet().remove(rankKeys.get(i), member);
            }
            rankWriteStamps.remove(shopId);
        } catch (Exception e) {
            log.error("从热度榜摘除门店失败, shopId={}", shopId, e);
        }
    }

    /** 门店类型字典的 id 集合（进程内缓存，见 typeNameOf） */
    private Set<Long> allTypeIds() {
        java.util.Map<Long, String> cache = this.typeNameCache;
        if (cache == null) {
            cache = loadTypeNameCache();
        }
        return cache.keySet();
    }

    /**
     * 重建 GEO 索引：写入临时 key，写完整体 {@code RENAME} 覆盖正式 key。
     *
     * 【注意】不能"先 DEL 再逐条 add"：中间会有一段索引为空的窗口，并发 nearby 查到空结果且不报错
     * （静默的错误答案）；RENAME 是原子的，查询侧要么看到旧索引要么看到新索引。
     * 【注意】最终成员集合由 RENAME 决定：新 key 只含本次写入的成员，旧索引里"有、新索引里没有"的成员
     * （停业/删除/坐标被清空）随整体替换一起消失，不靠逐条删。下面的成员差集作用在"即将被替换掉的旧 key"上，
     * 对最终成员集合没有影响，却让删除动作先落到了正式索引上 —— 它之后的步骤抛异常时，"正式索引未被改动"
     * 这一前提已不成立。
     */
    @Override
    public int rebuildGeoIndex() {
        List<Shop> shops = lambdaQuery().eq(Shop::getStatus, STATUS_OPEN).list();
        String tempKey = SHOP_GEO_KEY + ":rebuild:" + System.currentTimeMillis();
        int count = 0;
        try {
            List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                public Object execute(RedisOperations operations) {
                    for (Shop shop : shops) {
                        if (shop.getX() == null || shop.getY() == null) {
                            continue;
                        }
                        operations.opsForGeo().add(tempKey,
                                new Point(shop.getX(), shop.getY()), String.valueOf(shop.getId()));
                    }
                    return null;
                }
            });
            for (Object r : results) {
                if (r instanceof Long && (Long) r > 0) {
                    count++;
                }
            }
            // 差集：旧索引里有、新索引里没有的成员（停业/删除/坐标被清空的门店）→ 显式删除
            Set<String> stale = redisTemplate.opsForZSet().range(SHOP_GEO_KEY, 0, -1);
            if (CollUtils.isNotEmpty(stale)) {
                stale.removeAll(membersOf(tempKey));
                if (!stale.isEmpty()) {
                    redisTemplate.opsForZSet().remove(SHOP_GEO_KEY, stale.toArray());
                    log.info("GEO 索引清理：移除 {} 个已停业/已删除的门店坐标", stale.size());
                }
            }
            if (count == 0) {
                // 一个有效坐标都没有：清掉正式 key，避免"查得到但全是失格门店"
                redisTemplate.delete(SHOP_GEO_KEY);
            } else {
                redisTemplate.rename(tempKey, SHOP_GEO_KEY);
            }
            if (rankTtlMinutes > 0 && count > 0) {
                redisTemplate.expire(SHOP_GEO_KEY, Duration.ofMinutes(rankTtlMinutes));
            }
        } catch (Exception e) {
            log.error("GEO 索引重建失败（正式索引未被改动，仍是重建前的可用状态）", e);
            throw e;
        } finally {
            // 成功失败都要清临时 key（RENAME 成功后它已不存在，删除是幂等的）
            try {
                redisTemplate.delete(tempKey);
            } catch (Exception ignore) {
                // 临时 key 没有 TTL 会成为垃圾：这里失败只记日志，不覆盖上面的主异常
                log.warn("清理 GEO 临时 key 失败，需要人工删除: key={}", tempKey);
            }
        }
        log.info("GEO 索引重建完成，共写入 {} 个门店（原子替换，无服务窗口）", count);
        return count;
    }

    /** 读取某个 GEO/ZSet key 的全部成员名（用于重建时算差集） */
    private Set<String> membersOf(String key) {
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * 记录一次门店访问（HyperLogLog 统计 UV）：PFADD 与"仅当没有 TTL 时才设 TTL"合并成一次 Lua 往返，
     * 不存在"写了值但没设 TTL"的中间态。
     *
     * 【注意】拆成 PFADD + EXPIRE 两条命令时，第二条失败或进程在两条之间退出，key 就永久没有 TTL，
     * 而 uv key 按天新建、无 TTL 会按"天数 × 门店数"累积（现役 Redis 为 noeviction，不淘汰）。
     * 【注意】{@code PEXPIRE ... NX} 只在没有 TTL 时设置，否则每次访问都续期等于永不过期。
     */
    @Override
    public void recordVisit(Long shopId, Long userId) {
        if (shopId == null) {
            return;
        }
        // 未登录访客统一用 "guest"：HyperLogLog 的 UV 口径是"登录用户数 + 匿名 1"，匿名流量不做去重，
        // 要精确统计匿名 UV 需按 IP/session 生成随机标识，本项目不追求该精度
        String visitor = userId == null ? "guest" : String.valueOf(userId);
        String uvKey = SHOP_UV_KEY + LocalDate.now() + ":" + shopId;
        String pvKey = SHOP_PV_KEY + LocalDate.now() + ":" + shopId;
        // HyperLogLog 是估算结构，误差 0.81%，只占 12KB；PV 是计数器，同一次往返里一起写
        long ttlMillis = Duration.ofDays(uvTtlDays).toMillis();
        try {
            Long ttlSet = redisTemplate.execute(VISIT_UV_SCRIPT, Arrays.asList(uvKey, pvKey),
                    visitor, String.valueOf(ttlMillis));
            if (ttlSet != null && ttlSet > 0) {
                log.debug("访问计数器首次创建并设置 TTL: uvKey={}, ttl={}天", uvKey, uvTtlDays);
            }
        } catch (Exception e) {
            // 访问统计是旁路数据，写失败绝不能影响"用户在看门店"这件事
            log.warn("记录门店访问失败（UV/PV 统计有损，不影响主流程）: shopId={}", shopId, e);
        }
    }

    @Override
    public long queryUv(Long shopId) {
        Long size = redisTemplate.opsForHyperLogLog().size(SHOP_UV_KEY + LocalDate.now() + ":" + shopId);
        return size == null ? 0L : size;
    }

    @Override
    public void evictCache(Long shopId) {
        // 运维手动失效：与重建共用同一把锁，避免"重建线程刚写到一半、删除把新值删掉"的交叉。
        // 拿不到锁时短等（重建通常只有毫秒级），仍拿不到则直接删并留日志，保证运维动作不被无限阻塞。
        evictAllShopDerivedData(shopId);
        log.info("门店缓存已失效（详情 + 榜单 + GEO 坐标）, shopId={}", shopId);
    }

    /**
     * 门店下线/删除时的派生数据清理：详情缓存、热度榜成员、GEO 坐标三份都由门店派生，
     * 但只有详情缓存能"过期即自愈"（读时会回源重建），榜单成员要等整个 key 过期、GEO 成员只能靠重建索引清，
     * 所以门店变为非营业时必须显式摘掉，否则僵尸成员会占着榜单候选名次、nearby 每次白回源一次。
     *
     * 调用时机：门店下架/删除（写操作）之后，或运维手动失效。
     */
    @Override
    public void markShopOffline(Long shopId) {
        if (shopId == null) {
            return;
        }
        evictAllShopDerivedData(shopId);
        log.info("门店下线清理完成（榜单 + GEO + 详情缓存）, shopId={}", shopId);
    }

    /** 三份派生数据一起删：详情缓存 key、榜单成员、GEO 坐标 */
    private void evictAllShopDerivedData(Long shopId) {
        deleteCacheUnderLock(shopId);
        removeFromRank(shopId);
        try {
            redisTemplate.opsForZSet().remove(SHOP_GEO_KEY, String.valueOf(shopId));
        } catch (Exception e) {
            log.error("从 GEO 索引移除门店失败, shopId={}", shopId, e);
        }
    }

    /**
     * 在重建锁的保护下删除详情缓存。
     *
     * 【为什么删除也要拿锁】重建线程的时序是"查库 -> 写缓存"。若删除发生在查库之后、写缓存之前，
     * 新值会被随后的写入覆盖成旧值；把删除与重建串行化后，只可能出现"先写后删（自愈）"或
     * "先删后写新值"两种顺序，不会留下旧值。
     */
    private void deleteCacheUnderLock(Long shopId) {
        String lockKey = SHOP_LOCK_KEY + shopId;
        String token = tryLock(lockKey);
        long deadline = System.currentTimeMillis() + lockWaitMillis;
        while (token == null && System.currentTimeMillis() < deadline) {
            sleepQuietly(lockWaitIntervalMillis);
            token = tryLock(lockKey);
        }
        if (token == null) {
            log.warn("失效门店缓存时未取得重建锁，直接删除（并发重建可能写回旧值）, shopId={}", shopId);
        }
        try {
            deleteCacheKey(SHOP_CACHE_KEY + shopId);
        } finally {
            if (token != null) {
                unlock(lockKey, token);
            }
        }
    }

    /**
     * 门店缓存失效：必须注册到事务提交之后执行。
     *
     * 【注意】在事务里直接 delete 会出问题：删除发生在本事务提交之前，此刻并发读请求发现缓存没了就去查 DB，
     * 读到的是尚未提交的旧值，又把这个旧值写回缓存（最长 ttl 分钟），于是 DB 已是新值、缓存还是旧值。
     * 回滚时则什么都不做：DB 与缓存都是旧值，删了反而平白引发一次回源（并发下还可能被击穿）。
     */
    private void evictCacheAfterCommit(Long shopId) {
        String cacheKey = SHOP_CACHE_KEY + shopId;
        TxAfterCommit.run("evict shop cache id=" + shopId, () -> deleteCacheKey(cacheKey));
    }

    /**
     * 真正删 key：必须吞掉 Redis 异常只记日志。
     * 【注意】afterCommit 里抛出的异常会顺着 TransactionSynchronization 传播给提交事务的调用方，
     * 让一个已经提交成功的事务在调用方看来是失败的（上层可能重试甚至回滚 MQ 消费）。
     */
    private void deleteCacheKey(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.error("失效门店缓存失败, key={}", cacheKey, e);
        }
    }

    /**
     * 门店实体 → DTO：两处口径修正，错了不报错、只会静默显示错数字。
     *
     * 【注意】avg_price 存的是「元」，而对外契约（ShopDTO / ItemDTO.price）与前端 money() 统一用「分」，必须乘 100。
     * 【注意】type_id 是外键，必须按字典表回填 typeName：前端门店卡片的类型标签、以及搜索域复制 DTO 后
     * 按类型词（美食/亲子/酒吧）检索都依赖它，缺失时标签为空、搜类型词无结果。
     */
    private ShopDTO toDTO(Shop shop) {
        ShopDTO dto = BeanUtils.copyBean(shop, ShopDTO.class);
        if (dto == null) {
            return null;
        }
        if (dto.getAvgPrice() != null) {
            dto.setAvgPrice(dto.getAvgPrice() * 100);
        }
        dto.setTypeName(typeNameOf(shop.getTypeId()));
        return dto;
    }

    /** 按 type_id 取类型名；字典表内容固定，首次查询后在进程内缓存 */
    private String typeNameOf(Long typeId) {
        if (typeId == null) {
            return null;
        }
        java.util.Map<Long, String> cache = this.typeNameCache;
        if (cache == null) {
            cache = loadTypeNameCache();
        }
        return cache.get(typeId);
    }

    /** 装载门店类型字典到进程内缓存并返回（typeNameOf 与 allTypeIds 共用，避免两处各写一遍） */
    private java.util.Map<Long, String> loadTypeNameCache() {
        java.util.Map<Long, String> cache = shopTypeMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.llmall.shop.domain.po.ShopType::getId,
                        com.llmall.shop.domain.po.ShopType::getName,
                        (a, b) -> a));
        this.typeNameCache = cache;
        return cache;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
