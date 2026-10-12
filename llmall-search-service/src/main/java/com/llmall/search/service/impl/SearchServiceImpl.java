package com.llmall.search.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.api.dto.ShopPageDTO;
import com.llmall.api.event.ShopHotChangedEvent;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.common.utils.CollUtils;
import com.llmall.search.domain.po.SearchHistory;
import com.llmall.search.domain.po.SearchHotWord;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.llmall.search.domain.vo.HotWordVO;
import com.llmall.search.domain.vo.SearchEngineStatusVO;
import com.llmall.search.es.EsShopSearchGateway;
import com.llmall.search.es.EsShopSearchGatewayImpl;
import com.llmall.search.es.ShopSearchEngineProperties;
import com.llmall.search.mapper.SearchHistoryMapper;
import com.llmall.search.mapper.SearchHotWordMapper;
import com.llmall.search.mapper.ShopSearchDocMapper;
import com.llmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llmall.search.constant.SearchRedisConstants.*;

/**
 * 搜索域实现：**ES 优先、MySQL 读模型兜底**。
 *
 * 引擎分工（llmall.search.engine = es / mysql / auto，默认 auto）：
 *   - ES：负责"检索"本身 —— IK 中文分词、多字段相关性、function_score 综合排序、geo 距离；
 *   - MySQL 读模型（shop_search_doc）：负责**权威数据**与**退路** ——
 *     ES 不可用/索引损坏时检索回落本表，不把"搜索挂了"升级成"接口 500"。
 * 两者以 shopId 为主键一一对应，可随时用 POST /search/index/rebuild 重灌，
 * 并用 GET /search/engine 对账（读模型条数 vs 索引文档数）。
 *
 * 关键设计（引入 ES 之前就成立的几条，没有因为换引擎而丢掉）：
 *  1. **读模型分离**：门店数据通过 shop.hot.changed 事件增量同步，检索路径上不跨服务调用；
 *  2. **事件幂等 + 防乱序**：视图更新是 upsert；用事件时间做版本比较，阻塞旧事件覆盖新数据；
 *  3. **热词两段式**：运行时 ZINCRBY 累加到 Redis ZSet，定时任务落库，避免高频写 MySQL。
 *
 * ES 同步的一条硬约束：ES 的写是**整文档替换**，没有局部更新语义。
 * 所以热点事件（只带 hotScore/comments/score）落库后必须**重新读一次完整行**再写 ES，
 * 否则会把名称、地址、坐标全刷成 null —— 这是接 ES 时最容易犯、也最难查的错误。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    private static final int STATUS_OPEN = 1;
    private static final int REBUILD_PAGE_SIZE = 100;
    /** 全量重建的页数上限保护，避免接口异常导致死循环 */
    private static final int REBUILD_MAX_PAGES = 100;

    private final ShopSearchDocMapper shopSearchDocMapper;
    private final SearchHotWordMapper hotWordMapper;
    private final SearchHistoryMapper historyMapper;
    private final StringRedisTemplate redisTemplate;
    private final ShopClient shopClient;
    /** ES 网关（检索加速 + 索引维护）；是否真正使用由 llmall.search.engine 决定 */
    private final EsShopSearchGateway esGateway;
    private final ShopSearchEngineProperties engineProperties;

    // ==================== 检索 ====================

    @Override
    public PageDTO<ShopDTO> search(String keyword, Long typeId, Integer page, Integer size, Long userId) {
        return doSearch(keyword, typeId, page, size, null, null, userId);
    }

    @Override
    public PageDTO<ShopDTO> searchNearby(String keyword, Long typeId, Integer page, Integer size,
                                         Double x, Double y, Long userId) {
        return doSearch(keyword, typeId, page, size, x, y, userId);
    }

    private PageDTO<ShopDTO> doSearch(String keyword, Long typeId, Integer page, Integer size,
                                      Double x, Double y, Long userId) {
        // 分页规则与其它服务统一走 PageQuery 的收敛方法；这里额外传 50，
        // 表示"检索接口的单页上限比全局上限更严"——保留原有语义，不做放宽
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10, 50);
        String kw = StrUtil.trimToNull(keyword);

        // ① ES 分支：engine=es/auto 时优先走 ES
        if (esGateway.enabled()) {
            try {
                PageDTO<ShopDTO> result = esGateway.search(kw, typeId, current, pageSize, x, y);
                recordSearch(kw, userId);
                return result;
            } catch (Exception e) {
                // engine=es 时不回落：对照压测要的是"纯 ES 的表现"，混进回落会让数据失真
                if (!engineProperties.fallbackToMysql()) {
                    throw new IllegalStateException("ES 检索失败且未开启回落: " + e.getMessage(), e);
                }
                log.warn("ES 检索失败，回落 MySQL 读模型: {}", e.getMessage());
            }
        }

        // ② MySQL 分支（原有实现逐字保留）：也是 ES 的兜底
        LambdaQueryWrapper<ShopSearchDoc> wrapper = new LambdaQueryWrapper<ShopSearchDoc>()
                .eq(typeId != null, ShopSearchDoc::getTypeId, typeId)
                .eq(ShopSearchDoc::getStatus, STATUS_OPEN)
                // 关键词命中 名称 / 门店类型 / 商圈 / 地址 任一即可。
                // 【补充】原来只匹配 名称/商圈/地址，导致"美食""KTV""丽人·美发"这类**类型词**搜不到任何门店 ——
                // 而首页的分类 chip 传的正是类型名，用户点进去就是空白页。这里把 typeName 也纳入匹配。
                .and(kw != null, w -> w.like(ShopSearchDoc::getName, kw)
                        .or().like(ShopSearchDoc::getTypeName, kw)
                        .or().like(ShopSearchDoc::getArea, kw)
                        .or().like(ShopSearchDoc::getAddress, kw))
                // 排序主键是热度分：这就是"评价 → 热度 → 搜索排序"闭环的最后一跳
                .orderByDesc(ShopSearchDoc::getHotScore)
                .orderByDesc(ShopSearchDoc::getComments);

        Page<ShopSearchDoc> result = shopSearchDocMapper.selectPage(new Page<>(current, pageSize), wrapper);

        recordSearch(kw, userId);
        return PageDTO.of(result, this::toShopDTO);
    }

    /** 关键词统计与历史：两条引擎分支共用（避免"换了引擎热词就不统计了"这种副作用差异） */
    private void recordSearch(String kw, Long userId) {
        if (kw == null) {
            return;
        }
        recordKeyword(kw);
        if (userId != null) {
            saveHistory(userId, kw);
        }
    }

    /** 热词累加：ZINCRBY 是原子操作，天然并发安全；超长词直接丢弃 */
    private void recordKeyword(String keyword) {
        if (keyword.length() > HOT_WORD_MAX_LENGTH) {
            return;
        }
        redisTemplate.opsForZSet().incrementScore(HOT_WORD_KEY, keyword, 1D);
    }

    private void saveHistory(Long userId, String keyword) {
        // 同一用户同一关键词只保留最近一条：先查再插（数据量小，成本可接受）
        SearchHistory exist = historyMapper.selectOne(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId)
                .eq(SearchHistory::getKeyword, keyword));
        if (exist != null) {
            return;
        }
        historyMapper.insert(new SearchHistory().setUserId(userId).setKeyword(keyword));
    }

    @Override
    public List<HotWordVO> hotWords(Integer limit) {
        int size = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(HOT_WORD_KEY, 0, size - 1);
        List<HotWordVO> list = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() != null) {
                    list.add(new HotWordVO(tuple.getValue(), tuple.getScore()));
                }
            }
        }
        return list;
    }

    @Override
    public List<String> suggest(String prefix, Integer limit) {
        String kw = StrUtil.trimToNull(prefix);
        if (kw == null) {
            return CollUtils.emptyList();
        }
        int size = (limit == null || limit <= 0) ? 10 : Math.min(limit, 20);
        // 前缀匹配走索引（likeRight 等价于 word LIKE 'kw%'）；数据量再大或要拼音/纠错时再上 ES 的 completion suggester
        List<SearchHotWord> words = hotWordMapper.selectList(new LambdaQueryWrapper<SearchHotWord>()
                .likeRight(SearchHotWord::getWord, kw)
                .orderByDesc(SearchHotWord::getSearchCount)
                .last("LIMIT " + size));
        return words.stream().map(SearchHotWord::getWord).distinct().collect(Collectors.toList());
    }

    @Override
    public List<SearchHistory> myHistory(Long userId, Integer limit) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        int size = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        return historyMapper.selectList(new LambdaQueryWrapper<SearchHistory>()
                .eq(SearchHistory::getUserId, userId)
                .orderByDesc(SearchHistory::getId)
                .last("LIMIT " + size));
    }

    @Override
    public void clearHistory(Long userId) {
        if (userId == null) {
            return;
        }
        historyMapper.delete(new LambdaQueryWrapper<SearchHistory>().eq(SearchHistory::getUserId, userId));
    }

    // ==================== 视图维护（亮点 C 的最后一跳）====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyShopHotChanged(ShopHotChangedEvent event) {
        if (event == null || event.getShopId() == null) {
            return;
        }
        LocalDateTime eventTime = event.getOccurredAt() == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getOccurredAt()), ZoneId.systemDefault());

        ShopSearchDoc exist = shopSearchDocMapper.selectById(event.getShopId());
        // 防乱序：MQ 不保证顺序，若旧事件后到会把新数据覆盖回去（典型"数据回退"故障）
        if (exist != null && exist.getEventTime() != null && eventTime.isBefore(exist.getEventTime())) {
            log.info("收到过期的热度事件，忽略: shopId={}, eventTime={} < 已处理 {}",
                    event.getShopId(), eventTime, exist.getEventTime());
            return;
        }

        ShopSearchDoc doc = new ShopSearchDoc()
                .setShopId(event.getShopId())
                .setHotScore(event.getHotScore() == null ? 0D : event.getHotScore())
                .setComments(event.getComments())
                .setScore(event.getScore())
                .setEventTime(eventTime);

        if (exist == null) {
            // 视图里还没有该门店（事件早于全量重建）→ 主动拉一次门店详情补全，避免脏数据
            ShopDTO shop = shopClient.queryShopById(event.getShopId());
            if (shop == null) {
                log.warn("视图补全失败：门店不存在, shopId={}", event.getShopId());
                return;
            }
            fillFromShop(doc, shop);
            shopSearchDocMapper.insert(doc);
            log.info("搜索视图新增: shopId={}, hotScore={}", event.getShopId(), doc.getHotScore());
        } else {
            // 只更新事件携带的字段（MyBatis-Plus update-strategy=not_null），名称/地址等由重建任务维护
            shopSearchDocMapper.updateById(doc);
            log.info("搜索视图更新: shopId={}, hotScore={}", event.getShopId(), doc.getHotScore());
        }

        // 同步 ES：上面的 doc 只有热点字段，必须重新读完整行再写
        // （ES 的写是整文档替换，拿局部 doc 去写会把名称/地址/坐标刷成 null）
        syncToEs(event.getShopId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int rebuildSearchView() {
        int page = 1;
        int total = 0;
        while (page <= REBUILD_MAX_PAGES) {
            ShopPageDTO dto = shopClient.queryShopPage(page, REBUILD_PAGE_SIZE);
            if (dto == null || CollUtils.isEmpty(dto.getList())) {
                break;
            }
            for (ShopDTO shop : dto.getList()) {
                upsertFromShop(shop);
                total++;
            }
            if (dto.getPages() == null || page >= dto.getPages()) {
                break;
            }
            page++;
        }
        log.info("搜索视图重建完成，共同步 {} 条", total);
        // 读模型重建完，索引一并重灌：否则 ES 会停在旧数据上（"重建了却搜不到新的"）
        rebuildEsIndexQuietly();
        return total;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncHotWordsToDb() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(HOT_WORD_KEY, 0, HOT_WORD_SYNC_TOP - 1);
        if (CollUtils.isEmpty(tuples)) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        int count = 0;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String word = tuple.getValue();
            if (StrUtil.isBlank(word)) {
                continue;
            }
            long searchCount = tuple.getScore() == null ? 0L : tuple.getScore().longValue();
            SearchHotWord exist = hotWordMapper.selectOne(new LambdaQueryWrapper<SearchHotWord>()
                    .eq(SearchHotWord::getWord, word)
                    .eq(SearchHotWord::getStatDate, today));
            if (exist == null) {
                hotWordMapper.insert(new SearchHotWord()
                        .setWord(word).setSearchCount(searchCount).setStatDate(today));
            } else {
                exist.setSearchCount(searchCount);
                hotWordMapper.updateById(exist);
            }
            count++;
        }
        log.info("热词落库完成，共 {} 条", count);
        return count;
    }

    // ==================== 索引维护与对账 ====================

    @Override
    public int rebuildEsIndex() {
        if (!esGateway.enabled()) {
            log.warn("[ES] 当前 engine={} 未启用 ES，忽略索引重建请求", engineProperties.getEngine());
            return 0;
        }
        esGateway.createIndexIfAbsent();
        return esGateway.reindexFromReadModel();
    }

    @Override
    public SearchEngineStatusVO engineStatus() {
        // selectCount 在 MyBatis-Plus 不同小版本里返回 Integer/Long 都有，
        // 这里按 Number 接住再取 longValue，避免踩版本差异
        Number mysqlCount = shopSearchDocMapper.selectCount(new LambdaQueryWrapper<ShopSearchDoc>());
        long mysqlDocs = mysqlCount == null ? 0L : mysqlCount.longValue();

        SearchEngineStatusVO vo = new SearchEngineStatusVO()
                .setEngine(engineProperties.getEngine())
                .setEsEnabled(esGateway.enabled())
                .setMysqlDocs(mysqlDocs);

        if (!esGateway.enabled()) {
            return vo.setEsHealthy(false).setEsDocs(-1L).setConsistent(false)
                    .setNote("engine=" + engineProperties.getEngine() + "，检索走 MySQL 读模型，未启用 ES");
        }
        try {
            long esDocs = esGateway.count();
            boolean same = esDocs == mysqlDocs;
            return vo.setEsHealthy(true).setEsDocs(esDocs).setConsistent(same)
                    .setNote(same ? "读模型与索引一致"
                            : "不一致（读模型 " + mysqlDocs + " vs 索引 " + esDocs
                              + "）：调 POST /search/index/rebuild 重建");
        } catch (Exception e) {
            return vo.setEsHealthy(false).setEsDocs(-1L).setConsistent(false)
                    .setNote("ES 不可用: " + e.getMessage());
        }
    }

    /** 把某门店的最新读模型行同步到 ES（best-effort：失败只告警，权威数据始终在 MySQL） */
    private void syncToEs(Long shopId) {
        if (shopId == null || !esGateway.enabled()) {
            return;
        }
        try {
            ShopSearchDoc latest = shopSearchDocMapper.selectById(shopId);
            if (latest == null) {
                return;
            }
            esGateway.saveDocument(EsShopSearchGatewayImpl.toDocument(latest));
        } catch (Exception e) {
            log.warn("[ES] 文档同步失败（读模型已更新；可用 POST /search/index/rebuild 补齐）shopId={}, cause={}",
                    shopId, e.getMessage());
        }
    }

    /** 重建索引的"安静版"：供读模型重建流程内部调用，失败不让主流程失败 */
    private void rebuildEsIndexQuietly() {
        if (!esGateway.enabled()) {
            return;
        }
        try {
            int n = rebuildEsIndex();
            log.info("[ES] 索引已随读模型重建，共写入 {} 条", n);
        } catch (Exception e) {
            log.warn("[ES] 索引重建失败（读模型已重建，可单独调 POST /search/index/rebuild）: {}", e.getMessage());
        }
    }

    // ==================== 私有工具 ====================

    private void upsertFromShop(ShopDTO shop) {
        if (shop == null || shop.getId() == null) {
            return;
        }
        ShopSearchDoc doc = new ShopSearchDoc().setShopId(shop.getId());
        fillFromShop(doc, shop);
        doc.setEventTime(LocalDateTime.now());
        ShopSearchDoc exist = shopSearchDocMapper.selectById(shop.getId());
        if (exist == null) {
            shopSearchDocMapper.insert(doc);
        } else {
            shopSearchDocMapper.updateById(doc);
        }
    }

    private void fillFromShop(ShopSearchDoc doc, ShopDTO shop) {
        doc.setName(shop.getName())
                .setTypeId(shop.getTypeId())
                .setTypeName(shop.getTypeName())
                .setArea(shop.getArea())
                .setAddress(shop.getAddress())
                .setX(shop.getX())
                .setY(shop.getY())
                .setAvgPrice(shop.getAvgPrice())
                .setScore(shop.getScore() == null ? 0 : shop.getScore())
                .setComments(shop.getComments() == null ? 0 : shop.getComments())
                .setSold(shop.getSold() == null ? 0 : shop.getSold())
                .setHotScore(shop.getHotScore() == null ? 0D : shop.getHotScore())
                .setStatus(shop.getStatus() == null ? STATUS_OPEN : shop.getStatus());
    }

    private ShopDTO toShopDTO(ShopSearchDoc doc) {
        ShopDTO dto = new ShopDTO();
        dto.setId(doc.getShopId());
        dto.setName(doc.getName());
        dto.setTypeId(doc.getTypeId());
        dto.setTypeName(doc.getTypeName());
        dto.setArea(doc.getArea());
        dto.setAddress(doc.getAddress());
        dto.setX(doc.getX());
        dto.setY(doc.getY());
        dto.setAvgPrice(doc.getAvgPrice());
        dto.setScore(doc.getScore());
        dto.setComments(doc.getComments());
        dto.setSold(doc.getSold());
        dto.setHotScore(doc.getHotScore());
        dto.setStatus(doc.getStatus());
        return dto;
    }
}
