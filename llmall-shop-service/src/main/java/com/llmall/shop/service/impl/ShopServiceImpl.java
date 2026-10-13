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
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.CollUtils;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static com.llmall.shop.constant.ShopRedisConstants.*;

/**
 * 门店域服务实现。
 *
 * 这里集中了三类典型问题的处理，都是面试高频考点：
 *  1. 缓存穿透 —— 查不到的 id 也缓存空值（短 TTL），避免请求全部落到 DB；
 *  2. 缓存击穿 —— 热点数据用"逻辑过期 + 互斥锁 + 异步重建"，过期瞬间也始终有数据可返回；
 *  3. 缓存雪崩 —— TTL 加随机抖动，避免同一时刻大批 key 同时失效。
 * 另加热度分计算（时间衰减）、GEO 附近门店、HyperLogLog 统计 UV。
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** 营业中 */
    private static final int STATUS_OPEN = 1;

    /**
     * 释放锁的 Lua 脚本：**只有锁的值等于自己写入的 token 才删**，返回是否真删掉。
     *
     * 【为什么必须是 Lua】如果写成 Java 里的"先 get 比对、再 delete"，两次调用之间隔着一次网络往返，
     * 这期间自己的锁可能刚好超时过期、并被另一个线程重新持有 —— 于是 DEL 删掉的是**别人的锁**，
     * 两个线程同时进入重建路径，互斥形同虚设。Redis 执行脚本是单线程原子操作，比对与删除之间不会被打断。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

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

    /** 门店类型名缓存（10 条静态字典，进程内缓存即可，避免每次查库） */
    private volatile java.util.Map<Long, String> typeNameCache;

    /**
     * 事件发布（审计 B4）。热度是"评价 → 热度 → 搜索排序"闭环的中间产物，
     * 门店域算完必须让搜索域知道 —— 而这件事**不能**在事务里直发 MQ，
     * 所以统一交给 ShopEventPublisher 走"同事务登记本地消息表 + 提交后投递"。
     */
    private final ShopEventPublisher eventPublisher;

    /**
     * 显式构造器：缓存重建线程池有多个 Executor Bean（Boot 还会自动装配 applicationTaskExecutor），
     * 必须用 @Qualifier 指定，而 Lombok 默认不会把字段上的 @Qualifier 拷贝到构造参数上。
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

    // ==================== 缓存读取 ====================

    @Override
    public ShopDTO queryById(Long id) {
        if (id == null) {
            throw new BadRequestException("门店 id 不能为空");
        }
        String cacheKey = SHOP_CACHE_KEY + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);

        // 1. 缓存命中
        if (StrUtil.isNotBlank(cached)) {
            RedisData<ShopDTO> redisData = parseCache(cached);
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                return redisData.getData();
            }
            // 2. 逻辑过期：先返回旧数据（保证可用性），再异步重建（保证最终一致）
            String lockKey = SHOP_LOCK_KEY + id;
            String token = tryLock(lockKey);
            if (token != null) {
                cacheRebuildExecutor.execute(() -> {
                    try {
                        log.info("异步重建门店缓存, id={}", id);
                        rebuildCache(id);
                    } catch (Exception e) {
                        log.error("重建门店缓存失败, id={}", id, e);
                    } finally {
                        // 必须在 finally 里释放，否则重建异常会导致该门店永远无法再重建
                        unlock(lockKey, token);
                    }
                });
            }
            return redisData.getData();
        }

        // 3. 缓存未命中（冷 key，或空值哨兵已过期）：走互斥回源，避免同一 id 的并发请求一起打 DB
        return rebuildCacheWithMutex(id);
    }

    /**
     * 缓存未命中路径的互斥回源（防缓存击穿）。
     *
     * 【原来这里没有互斥】原实现只在"逻辑过期"分支加锁，真正未命中时是裸查 DB：
     * 一个冷门店被 N 个并发请求同时打，DB 就挨 N 次一模一样的查询。
     * 热门门店缓存刚过期/刚被删的瞬间最危险 —— 那正是缓存击穿的定义。
     * 现在两条路径共用同一把 lock:shop:{id}。
     *
     * 抢不到锁的请求**不发 DB 查询**，而是短轮询等持锁线程把结果（数据或空值哨兵）写进缓存：
     *  - 等到了 → 直接返回，同一个冷 key 的 DB 查询只发生 1 次；
     *  - 等到超时（默认 500ms）→ 兜底自己回源一次，保证请求不会一直空等；
     *    兜底查询**不回填缓存**（写缓存是持锁线程的职责，否则两个线程会互相覆盖，
     *    而且会把"谁先写谁生效"的竞争结果留在缓存里）。
     */
    private ShopDTO rebuildCacheWithMutex(Long id) {
        String cacheKey = SHOP_CACHE_KEY + id;
        String lockKey = SHOP_LOCK_KEY + id;
        String token = tryLock(lockKey);
        if (token != null) {
            try {
                // 二次检查：抢到锁之前可能已经有人重建完并释放了锁，此时没必要再查一次 DB
                String recheck = redisTemplate.opsForValue().get(cacheKey);
                if (StrUtil.isNotBlank(recheck)) {
                    return parseCache(recheck).getData();
                }
                return rebuildCache(id);
            } finally {
                unlock(lockKey, token);
            }
        }
        long deadline = System.currentTimeMillis() + lockWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            sleepQuietly(lockWaitIntervalMillis);
            String again = redisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(again)) {
                return parseCache(again).getData();
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        log.warn("等待门店缓存重建超时，降级为直接回源 DB（不回填缓存）, id={}", id);
        Shop shop = getById(id);
        return shop == null ? null : toDTO(shop);
    }

    /** 等待重建结果时的短睡眠：被中断就恢复中断标记并退出等待（不能吞掉中断，否则优雅停机失效） */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 查库并回填缓存；门店不存在时写入空值哨兵。两种 key 都必须带 Redis 物理 TTL */
    private ShopDTO rebuildCache(Long id) {
        Shop shop = getById(id);
        ShopDTO dto = shop == null ? null : toDTO(shop);
        long logicalMinutes = shop == null
                // 空值哨兵：短 TTL + 抖动（防大批"不存在的 id"同时过期同时回源）
                ? nullCacheTtlMinutes + ThreadLocalRandom.current().nextLong(nullCacheTtlJitterMinutes + 1)
                // 正常数据：基础 TTL + 随机抖动，防雪崩
                : cacheTtlMinutes + ThreadLocalRandom.current().nextLong(cacheTtlJitterMinutes + 1);
        long physicalMinutes = shop == null ? logicalMinutes : logicalMinutes + physicalExtraMinutes;
        RedisData<ShopDTO> redisData = RedisData.of(dto, LocalDateTime.now().plusMinutes(logicalMinutes));
        try {
            // 【必须带过期时间】原来这里 set 不带 TTL，写入的 key 永久驻留：
            // 匿名刷 GET /shops/{随机id}（不存在的 id 也会写空值哨兵）会让 Redis 内存单调增长，
            // 最终把真正有用的业务缓存挤掉。实测（改前）：刷 50 个随机 id 后 cache:shop:* 共 51 个 key，
            // TTL 全部为 -1（永不过期）；改后每个 key 的 TTL 都 > 0，key 数量被 TTL 限住。
            redisTemplate.opsForValue().set(SHOP_CACHE_KEY + id,
                    objectMapper.writeValueAsString(redisData),
                    Duration.ofMinutes(physicalMinutes));
        } catch (Exception e) {
            // 写缓存失败不能影响主流程：降级为直接返回 DB 数据
            log.error("写入门店缓存失败, id={}", id, e);
        }
        return dto;
    }

    private RedisData<ShopDTO> parseCache(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<RedisData<ShopDTO>>() {
            });
        } catch (Exception e) {
            throw new BizIllegalException("门店缓存数据解析失败", e);
        }
    }

    /**
     * 加锁：value 用本次调用生成的 UUID，返回该 token（没抢到返回 null）。
     *
     * 【为什么不能用固定值】原来是写死 "1"：锁里没有任何"所有者"信息，
     * 于是释放时只能无条件 DEL。一旦自己的锁已经超时（重建比 LOCK_TTL_SECONDS 还慢）而被别人重新持有，
     * 自己收尾时的 DEL 就会删掉**新持有者的锁**，第三个请求又能立刻抢到锁 ——
     * 互斥被击穿，"同一个 key 只允许一个线程回源"直接失效。
     */
    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, token, Duration.ofSeconds(lockTtlSeconds));
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /**
     * 解锁：在 Redis 侧原子地"比对 value + 删除"（Lua），只有自己加的锁才删得掉。
     * 这样即使本次持锁已经超时、锁已被别人拿走，这里的释放也不会误删别人的锁（返回 false）。
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

    // ==================== 查询 ====================

    @Override
    public List<ShopDTO> queryByIds(Collection<Long> ids) {
        if (CollUtils.isEmpty(ids)) {
            return CollUtils.emptyList();
        }
        // 统一走 toDTO：实体 → DTO 的单位换算（人均元→分）与类型名回填只在这里做一处，
        // 否则"详情页对了、列表页还是旧数据"这种不一致会到处冒出来
        return listByIds(ids).stream().map(this::toDTO).collect(java.util.stream.Collectors.toList());
    }

    @Override
    public PageDTO<ShopDTO> pageQuery(Long typeId, String keyword, Integer page, Integer size) {
        // 注意：本项目 MyBatis-Plus 为 3.4.2，Page.of(...) 是 3.5+ 才有的静态工厂，这里用构造器
        // 【为什么用公共件的收敛方法而不是就地 if-else】原来只挡了 size < 1 这一侧，
        // size = 100000 会原样落进 SQL 变成一次大结果集查询；分页上下限必须全项目一套规则，
        // 否则"新增一个分页接口忘了加判断"又会漏一个洞。
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10);
        Page<Shop> result = lambdaQuery()
                .eq(typeId != null, Shop::getTypeId, typeId)
                .eq(Shop::getStatus, STATUS_OPEN)
                .like(StrUtil.isNotBlank(keyword), Shop::getName, keyword)
                // 按热度分排序：热度由评价事件异步更新（亮点 C 的读路径）
                .orderByDesc(Shop::getHotScore)
                .orderByDesc(Shop::getComments)
                .page(new Page<>(current, pageSize));
        // 【修复】原来直接用 PageDTO.of(page, ShopDTO.class) 批量 copyBean，
        // 绕过了 toDTO，导致列表接口的人均还是"元"、类型名为空（前端门店卡片既不显示类型标签、
        // 人均还显示成 ¥0.8）。这里改为显式走 toDTO，保证详情与列表口径一致。
        List<ShopDTO> list = result.getRecords().stream().map(this::toDTO).collect(java.util.stream.Collectors.toList());
        return PageDTO.of(result, list);
    }

    /**
     * 热度榜单。
     * 注意：DB 的 IN 查询返回顺序不保证与 ZSet 一致，必须"按榜单 id 顺序还原"，
     * 否则榜单会莫名其妙乱序 —— 这是用 ZSet 做榜单时最常见的坑。
     */
    @Override
    public List<ShopDTO> queryHotList(Long typeId, Integer limit) {
        int size = (limit == null || limit <= 0) ? 10 : Math.min(limit, 100);
        String rankKey = typeId == null ? SHOP_HOT_RANK_ALL : SHOP_HOT_RANK_KEY + typeId;

        Set<String> idSet = redisTemplate.opsForZSet().reverseRange(rankKey, 0, size - 1);
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
        List<ShopDTO> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Shop shop = shopMap.get(id);
            if (shop != null) {
                result.add(toDTO(shop));
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
            // GEO 索引未预热（首次部署、Redis 重启后数据丢失）时回源 DB 重建一次再查，
            // 与 queryHotList 的"回源预热"保持一致，避免"附近门店"长期空白。
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

    // ==================== 热度分（亮点 C 的计算核心）====================

    /**
     * 热度分 = base × 时间衰减
     *   base  = score/10×4（评分，权重最高，除以 10 是因为评分按 ×10 存储）
     *         + log10(comments+1)×3（评价数，用 log 抑制刷评的边际效果）
     *         + log10(sold+1)×3（销量，同样用 log 抑制爆款通吃）
     *   decay = 1 / (1 + Δ天/7)：7 天衰减一半，让新店有冷启动机会、老店需要持续经营
     * 锚点取"最近一次评价时间"，而不是重算时间——否则每次重算都把衰减重置，衰减就失效了。
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

        // 通知搜索域：这条事件与上面的热度/销量更新**处于同一个本地事务**
        // （审计 B4：原来是在 ReviewCreatedListener 的事务里直接 convertAndSend，
        //  投递失败只 log.error；现在改为同事务登记 llmall-shop.message_log + 提交后投递，
        //  投递失败由 MessageRetryTask 退避重发，下游没处理由 ShopSearchReconcileTask 对账补发）。
        // 事件体是**整行门店快照**（含 sold/status），搜索域因此可以整行 upsert，
        // 不再出现"sold 只在全量重建时才更新"。
        // 放在 recomputeHotScore 里而不是各个调用方里：凡是改热度的路径（评价、订单支付、运维重算）
        // 都必然经过这里，新增调用方不会再漏发事件。
        eventPublisher.publishHotChanged(buildDocChangedEvent(shop, hotScore));

        log.info("门店热度已重算: shopId={}, base={}, decay={}, hotScore={}",
                shopId, String.format("%.4f", base), String.format("%.4f", decay), hotScore);
        return hotScore;
    }

    /**
     * 用**当前事务内读到的** shop 行构造搜索域需要的整行快照。
     *
     * 两个必须注意的口径（都是"错了不报错、只显示错数字"的那类坑）：
     *  1. {@code avgPrice} 单位：shop.avg_price 存的是「元」，而搜索读模型与前端契约都是「分」，
     *     这里乘 100（与 toDTO 同一口径）。漏了这步门店卡片会显示成 ¥0.8。
     *  2. {@code typeName}：shop 表里只有 type_id，检索要用类型名，所以按字典表回填
     *     （typeNameOf 有进程内缓存，代价可忽略）。
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
                // 元 → 分（见方法注释第 1 条）
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
        // 评分变化会影响详情缓存，主动失效，下次读取时重建。
        // 本方法通常运行在 ReviewCreatedListener 的事务里（占位 + 累加同事务），
        // 所以这里同样是"提交后才失效"；万一没有事务上下文，则立即删除。
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
                .eq(ConsumeRecord::getConsumer, "llmall-shop-service"));
        return count != null && count > 0;
    }

    private void addToRank(Shop shop) {        double hot = shop.getHotScore() == null ? 0D : shop.getHotScore();
        String member = String.valueOf(shop.getId());
        redisTemplate.opsForZSet().add(SHOP_HOT_RANK_ALL, member, hot);
        if (shop.getTypeId() != null) {
            redisTemplate.opsForZSet().add(SHOP_HOT_RANK_KEY + shop.getTypeId(), member, hot);
        }
    }

    // ==================== GEO / UV / 缓存维护 ====================

    @Override
    public int rebuildGeoIndex() {
        List<Shop> shops = lambdaQuery().eq(Shop::getStatus, STATUS_OPEN).list();
        redisTemplate.delete(SHOP_GEO_KEY);
        int count = 0;
        for (Shop shop : shops) {
            if (shop.getX() == null || shop.getY() == null) {
                continue;
            }
            redisTemplate.opsForGeo().add(SHOP_GEO_KEY,
                    new Point(shop.getX(), shop.getY()), String.valueOf(shop.getId()));
            count++;
        }
        log.info("GEO 索引重建完成，共写入 {} 个门店", count);
        return count;
    }

    @Override
    public void recordVisit(Long shopId, Long userId) {
        if (shopId == null) {
            return;
        }
        // 未登录访客统一用 "guest" 标识（与代码一致）：HyperLogLog 的 UV 口径是"登录用户数 + 匿名 1"，
        // 匿名流量不做去重。要精确统计匿名 UV 需按 IP/session 生成随机标识，本项目不追求该精度。
        String visitor = userId == null ? "guest" : String.valueOf(userId);
        String key = SHOP_UV_KEY + LocalDate.now() + ":" + shopId;
        redisTemplate.opsForHyperLogLog().add(key, visitor);
        // HyperLogLog 是估算结构，误差 0.81%，但只占 12KB，适合亿级 UV 统计
        redisTemplate.expire(key, Duration.ofDays(2));
    }

    @Override
    public long queryUv(Long shopId) {
        Long size = redisTemplate.opsForHyperLogLog().size(SHOP_UV_KEY + LocalDate.now() + ":" + shopId);
        return size == null ? 0L : size;
    }

    @Override
    public void evictCache(Long shopId) {
        // 运维手动失效（DELETE /shops/cache/{id}）：这是外部主动动作，不在事务里，直接删
        deleteCacheKey(SHOP_CACHE_KEY + shopId);
        log.info("门店缓存已失效, shopId={}", shopId);
    }

    /**
     * 门店缓存失效：必须注册到**事务提交之后**执行。
     *
     * 【原来错在哪】原实现在事务里直接 redisTemplate.delete：
     * 删除发生在本事务提交之前，此刻并发读请求发现缓存没了，就去查 DB ——
     * 它读到的是**旧值**（本事务还没提交），又把这个旧值写回缓存（TTL 最长 30 分钟）。
     * 结果："DB 已经是新值、缓存是旧值"，详情页与列表页最长 30 分钟不一致。
     * 实测（改前）：UPDATE shop 在 12:44:10.792409、commit 在 12:44:10.798445，
     * 而 DEL cache:shop:112 在 12:44:10.796468 —— 确实早于提交。
     * 改到 afterCommit 后，"先删缓存造成的回填旧值"窗口被消掉：
     * 提交成功后缓存才失效，此后任何回源读到的都是已提交的新值。
     *
     * 事务**回滚**时则什么都不做：DB 回滚后还是旧值，缓存里也还是旧值，两者一致；
     * 这时删缓存不但没必要，还会平白引发一次回源（并发下还可能被击穿）。
     */
    private void evictCacheAfterCommit(Long shopId) {
        String cacheKey = SHOP_CACHE_KEY + shopId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteCacheKey(cacheKey);
                }
            });
            return;
        }
        // 没有事务上下文（例如直接调用、事务已结束）：立即删，行为与原来一致
        deleteCacheKey(cacheKey);
    }

    /**
     * 真正删 key。
     * afterCommit 里抛出的异常会顺着 TransactionSynchronization 传播给"提交事务的调用方"，
     * 让一个**已经提交成功**的事务在调用方看来是失败的（上层可能因此重试甚至回滚 MQ 消费），
     * 所以这里必须吞掉 Redis 异常，只记日志。
     */
    private void deleteCacheKey(String cacheKey) {
        try {
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            log.error("失效门店缓存失败, key={}", cacheKey, e);
        }
    }

    /**
     * 门店实体 → DTO。
     *
     * 【单位修正】llmall-shop.shop.avg_price 存的是「元」（点评原表就是人均元数，如 80 表示人均 80 元），
     * 而对外契约（ShopDTO / ItemDTO.price）统一用「分」，前端 money() 也按分渲染。
     * 原实现直接 copyBean，导致门店卡片显示"人均 ¥0.8"——典型的单位不一致缺陷，
     * 这种 bug 不会报错、只会静默显示错数字，比抛异常更难发现。
     *
     * 【类型名补全】type_id 是外键，原实现没有回填 typeName，于是：
     *   ① 门店卡片上的类型标签一直是空的；
     *   ② 搜索域复制 DTO 时 typeName 也是空 → 搜"美食""亲子""酒吧"这类**类型词**搜不到任何门店
     *      （首页分类 chip 传的就是类型名，点进去空白页）。
     * 这里按 type_id 回填（字典表只有 10 条，进程内缓存）。
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
            cache = shopTypeMapper.selectList(null).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.llmall.shop.domain.po.ShopType::getId,
                            com.llmall.shop.domain.po.ShopType::getName,
                            (a, b) -> a));
            this.typeNameCache = cache;
        }
        return cache.get(typeId);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
