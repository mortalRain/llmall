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
import com.llmall.shop.mapper.ConsumeRecordMapper;
import com.llmall.shop.mapper.ShopMapper;
import com.llmall.shop.mapper.ShopTypeMapper;
import com.llmall.shop.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Executor cacheRebuildExecutor;
    private final ConsumeRecordMapper consumeRecordMapper;
    private final ShopTypeMapper shopTypeMapper;

    /** 门店类型名缓存（10 条静态字典，进程内缓存即可，避免每次查库） */
    private volatile java.util.Map<Long, String> typeNameCache;

    /**
     * 显式构造器：缓存重建线程池有多个 Executor Bean（Boot 还会自动装配 applicationTaskExecutor），
     * 必须用 @Qualifier 指定，而 Lombok 默认不会把字段上的 @Qualifier 拷贝到构造参数上。
     */
    public ShopServiceImpl(StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor,
                           ConsumeRecordMapper consumeRecordMapper,
                           ShopTypeMapper shopTypeMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
        this.consumeRecordMapper = consumeRecordMapper;
        this.shopTypeMapper = shopTypeMapper;
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
            if (tryLock(lockKey)) {
                cacheRebuildExecutor.execute(() -> {
                    try {
                        log.info("异步重建门店缓存, id={}", id);
                        rebuildCache(id);
                    } catch (Exception e) {
                        log.error("重建门店缓存失败, id={}", id, e);
                    } finally {
                        // 必须在 finally 里释放，否则重建异常会导致该门店永远无法再重建
                        unlock(lockKey);
                    }
                });
            }
            return redisData.getData();
        }

        // 3. 缓存未命中：查库回填（含空值缓存，防穿透）
        return rebuildCache(id);
    }

    /** 查库并回填缓存；门店不存在时写入空值缓存（短 TTL） */
    private ShopDTO rebuildCache(Long id) {
        Shop shop = getById(id);
        ShopDTO dto = shop == null ? null : toDTO(shop);
        long ttlMinutes = shop == null
                ? NULL_CACHE_TTL_MINUTES
                // 正常数据：基础 TTL + 随机抖动，防雪崩
                : CACHE_TTL_MINUTES + ThreadLocalRandom.current().nextLong(CACHE_TTL_JITTER_MINUTES + 1);
        RedisData<ShopDTO> redisData = RedisData.of(dto, LocalDateTime.now().plusMinutes(ttlMinutes));
        try {
            redisTemplate.opsForValue().set(SHOP_CACHE_KEY + id, objectMapper.writeValueAsString(redisData));
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

    private boolean tryLock(String key) {
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(LOCK_TTL_SECONDS));
        return Boolean.TRUE.equals(ok);
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
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
        redisTemplate.delete(SHOP_CACHE_KEY + shopId);
        log.info("门店热度已重算: shopId={}, base={}, decay={}, hotScore={}",
                shopId, String.format("%.4f", base), String.format("%.4f", decay), hotScore);
        return hotScore;
    }

    @Override
    public int applyReview(Long shopId, Integer score) {
        if (shopId == null || score == null) {
            return 0;
        }
        // 一条 SQL 完成"读-算-写"，避免并发评价下丢更新（详见 ShopMapper#applyReview 的注释）
        int rows = baseMapper.applyReview(shopId, score);
        // 评分变化会影响详情缓存，主动失效，下次读取时重建
        if (rows > 0) {
            redisTemplate.delete(SHOP_CACHE_KEY + shopId);
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
        redisTemplate.delete(SHOP_CACHE_KEY + shopId);
        log.info("门店缓存已失效, shopId={}", shopId);
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
