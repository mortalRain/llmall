package com.llmall.search.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.api.dto.ShopPageDTO;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.common.utils.CollUtils;
import com.llmall.search.constant.SearchRedisScripts;
import com.llmall.search.domain.po.ConsumeRecord;
import com.llmall.search.domain.po.SearchHistory;
import com.llmall.search.domain.po.SearchHotWord;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.llmall.search.domain.vo.HotWordVO;
import com.llmall.search.domain.vo.SearchEngineStatusVO;
import com.llmall.search.es.EsShopSearchGateway;
import com.llmall.search.es.EsShopSearchGatewayImpl;
import com.llmall.search.es.ShopSearchEngineProperties;
import com.llmall.search.event.ShopDocChangedEvent;
import com.llmall.search.mapper.ConsumeRecordMapper;
import com.llmall.search.mapper.SearchHistoryMapper;
import com.llmall.search.mapper.SearchHotWordMapper;
import com.llmall.search.mapper.ShopSearchDocMapper;
import com.llmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llmall.search.constant.SearchRedisConstants.*;

/**
 * 搜索域实现：检索优先走 ES（IK 中文分词、多字段相关性、function_score、geo 距离），
 * MySQL 读模型 shop_search_doc 保存权威数据并在 ES 不可用时兜底（engine = es / mysql / auto）；
 * 热词两段式 —— 运行时 ZINCRBY 累加到 Redis ZSet，定时任务批量落库，避免高频写 MySQL。
 *
 * 【注意】ES 的写是整文档替换，没有局部更新语义：每次落库之后必须重新读回完整行再写索引，
 * 拿局部 doc 去写会把名称、地址、坐标全刷成 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    private static final int STATUS_OPEN = 1;
    private static final int REBUILD_PAGE_SIZE = 100;
    /** 全量重建的页数上限，防止对端分页异常时死循环 */
    private static final int REBUILD_MAX_PAGES = 100;

    /** 本域在 consume_record 中的消费者标识（服务名），门店域对账时按它查"我处理了没有" */

    private final ShopSearchDocMapper shopSearchDocMapper;
    private final SearchHotWordMapper hotWordMapper;
    private final SearchHistoryMapper historyMapper;
    /** 消费幂等记录：重投事件的闸门，也是门店域对账要问的"处理了没有"的判据 */
    private final ConsumeRecordMapper consumeRecordMapper;
    private final StringRedisTemplate redisTemplate;
    private final ShopClient shopClient;
    /** ES 网关（检索与索引维护）；是否真正使用由 llmall.search.engine 决定 */
    private final EsShopSearchGateway esGateway;
    private final ShopSearchEngineProperties engineProperties;

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
        // 检索接口的单页上限（50）刻意比 PageQuery 的全局上限更严，不要放宽
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10, 50);
        String kw = StrUtil.trimToNull(keyword);

        if (esGateway.enabled()) {
            try {
                PageDTO<ShopDTO> result = esGateway.search(kw, typeId, current, pageSize, x, y);
                recordSearch(kw, userId);
                return result;
            } catch (Exception e) {
                // engine=es 时刻意不回落：混入 MySQL 结果会让"纯 ES"的表现失真
                if (!engineProperties.fallbackToMysql()) {
                    throw new IllegalStateException("ES 检索失败且未开启回落: " + e.getMessage(), e);
                }
                log.warn("ES 检索失败，回落 MySQL 读模型: {}", e.getMessage());
            }
        }

        LambdaQueryWrapper<ShopSearchDoc> wrapper = new LambdaQueryWrapper<ShopSearchDoc>()
                .eq(typeId != null, ShopSearchDoc::getTypeId, typeId)
                .eq(ShopSearchDoc::getStatus, STATUS_OPEN)
                // 关键词命中 名称 / 门店类型 / 商圈 / 地址 任一即可；typeName 必须纳入匹配 ——
                // 首页分类 chip 传的就是类型名，漏掉它会让"美食""KTV"这类类型词搜不到门店
                .and(kw != null, w -> w.like(ShopSearchDoc::getName, kw)
                        .or().like(ShopSearchDoc::getTypeName, kw)
                        .or().like(ShopSearchDoc::getArea, kw)
                        .or().like(ShopSearchDoc::getAddress, kw))
                // 排序主键是热度分：评价 → 热度 → 搜索排序 闭环的最后一跳
                .orderByDesc(ShopSearchDoc::getHotScore)
                .orderByDesc(ShopSearchDoc::getComments);

        Page<ShopSearchDoc> result = shopSearchDocMapper.selectPage(new Page<>(current, pageSize), wrapper);

        recordSearch(kw, userId);
        return PageDTO.of(result, this::toShopDTO);
    }

    /** 关键词统计与历史记录：ES / MySQL 两条检索分支共用，避免换引擎后统计口径出现差异 */
    private void recordSearch(String kw, Long userId) {
        if (kw == null) {
            return;
        }
        recordKeyword(kw);
        if (userId != null) {
            saveHistory(userId, kw);
        }
    }

    /** 热词累加：计数 + 续期 + 截断一次原子往返，返回截断后的成员数；超长词直接丢弃 */
    private void recordKeyword(String keyword) {
        if (keyword.length() > HOT_WORD_MAX_LENGTH) {
            return;
        }
        // 写入口是匿名可访问的搜索接口：计数/续期/截断若拆成三条命令，任一条失败都会留下中间态 ——
        // 缺续期则热词 key 永久无 TTL（现役 Redis 是 maxmemory 0 + noeviction，既不过期也不淘汰），
        // score 退化成历史累计；缺截断则上界失效，而 TTL 又随写入不断续期，ZSet 在两次截断之间无限膨胀。
        // 合并成一次 Lua 往返后三者同生共死；截断也从"被动等下次查询"变为每次写入都执行，始终拉回上界。
        try {
            Long size = redisTemplate.execute(SearchRedisScripts.HOT_WORD_INCR_WITH_TTL_AND_TRIM,
                    Collections.singletonList(HOT_WORD_KEY),
                    keyword,
                    String.valueOf(HOT_WORD_TTL.toMillis()),
                    String.valueOf(HOT_WORD_MAX_SIZE));
            if (log.isDebugEnabled()) {
                log.debug("热词计数完成: word={}, zsetSize={}", keyword, size);
            }
        } catch (Exception e) {
            // 热词统计是旁路功能：Redis 抖动绝不能让"搜索"这个核心接口失败
            log.warn("热词统计失败（不影响检索结果）: word={}", keyword, e);
        }
    }

    private void saveHistory(Long userId, String keyword) {
        // 去重依赖表上的唯一键 (user_id, keyword)：先查后插在并发下必然写入重复行，
        // 而重复历史会让"最近搜索"出现两条一样的词。冲突本身即代表"已经有了"，直接吞掉。
        try {
            historyMapper.insert(new SearchHistory().setUserId(userId).setKeyword(keyword));
        } catch (DuplicateKeyException e) {
            // 正常路径不应刷 warn，冲突日志降级到 debug
            if (log.isDebugEnabled()) {
                log.debug("搜索历史已存在，忽略: userId={}, kw={}", userId, keyword);
            }
        }
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
        // 前缀匹配走索引（likeRight 等价于 word LIKE 'kw%'）；需要拼音/纠错时再换 ES 的 completion suggester
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

    /**
     * 消费门店文档变更事件 → 整行 upsert 本地读模型，并同步 ES。
     *
     * 【注意】新增路径要求事件带齐整行字段，未带齐则回源门店详情补全（见 hasFullRow）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyShopHotChanged(ShopDocChangedEvent event) {
        if (event == null || event.getShopId() == null) {
            return;
        }
        LocalDateTime eventTime = event.getOccurredAt() == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getOccurredAt()), ZoneId.systemDefault());
        // 【注意】事件时间必须先截断到秒：shop_search_doc.event_time 是 DATETIME（无小数秒），
        // 带毫秒写入会被 MySQL 四舍五入到下一秒，读回值比事件时间大，同一条事件重投（对账补发）时
        // 就会被下面的防乱序判断误判成过期而丢弃 —— consume_record 已记账，读模型却停在旧值。
        // 截断后写入值与读回值一致（.000）。防乱序仍有效，只是精度降到 1 秒，跨秒乱序照旧拦截。
        eventTime = eventTime.truncatedTo(ChronoUnit.SECONDS);

        ShopSearchDoc exist = shopSearchDocMapper.selectById(event.getShopId());
        // 防乱序：MQ 不保证顺序，旧事件后到会把新数据覆盖回去
        if (exist != null && exist.getEventTime() != null && eventTime.isBefore(exist.getEventTime())) {
            log.info("收到过期的热度事件，忽略: shopId={}, eventTime={} < 已处理 {}",
                    event.getShopId(), eventTime, exist.getEventTime());
            return;
        }

        ShopSearchDoc doc = new ShopSearchDoc().setShopId(event.getShopId()).setEventTime(eventTime);
        fillFromEvent(doc, event);

        if (exist == null) {
            if (!hasFullRow(event)) {
                // 兼容队列中只带热点字段的旧格式事件：回源门店详情补全整行，
                // 否则 NOT NULL 的 name/address/type_id 会让 insert 直接失败
                ShopDTO shop = shopClient.queryShopById(event.getShopId());
                if (shop == null) {
                    log.warn("视图补全失败：门店不存在, shopId={}", event.getShopId());
                    return;
                }
                fillFromShop(doc, shop);
            }
            shopSearchDocMapper.insert(doc);
            log.info("搜索视图新增: shopId={}, hotScore={}, sold={}",
                    event.getShopId(), doc.getHotScore(), doc.getSold());
        } else {
            shopSearchDocMapper.updateById(doc);
            log.info("搜索视图更新: shopId={}, hotScore={}, sold={}, comments={}",
                    event.getShopId(), doc.getHotScore(), doc.getSold(), doc.getComments());
        }

        // 重新读回落库后的权威行再写 ES（内存 doc 里可能含被 not_null 跳过的 null 字段）
        syncToEs(event.getShopId());
    }

    @Override
    public boolean isShopHotChangedConsumed(String bizId) {
        if (StrUtil.isBlank(bizId)) {
            return false;
        }
        // 判据 = 本域消费记录里有没有这一行；它与视图更新同事务写入（见 ShopHotChangedListener），
        // 因此"有行"必然意味着"视图真的更新成功了"
        Long count = consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getBizType, MqConstants.BIZ_SHOP_HOT_CHANGED)
                .eq(ConsumeRecord::getBizId, bizId)
                .eq(ConsumeRecord::getConsumer, MqConstants.CONSUMER_SEARCH));
        return count != null && count > 0;
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
        // 读模型重建后必须一并重灌索引，否则 ES 停在旧数据上（"重建了却搜不到新的"）
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
        // selectCount 在 MyBatis-Plus 不同小版本返回 Integer/Long 都有可能，按 Number 接住再取 longValue
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

    /** 重建索引的静默版：供读模型重建流程内部调用，失败不阻断主流程 */
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

    /**
     * 事件 → 读模型：整行快照逐字段搬运。
     *
     * update 路径上 null 字段会被 update-strategy=not_null 跳过，
     * 所以只带热点字段的事件落到已有文档上也不会把已有值刷空。
     */
    private void fillFromEvent(ShopSearchDoc doc, ShopDocChangedEvent event) {
        doc.setName(event.getName())
                .setTypeId(event.getTypeId())
                .setTypeName(event.getTypeName())
                .setArea(event.getArea())
                .setAddress(event.getAddress())
                .setX(event.getX())
                .setY(event.getY())
                // 事件里已经是「分」，与读模型/前端契约同口径，这里不要再 ×100
                .setAvgPrice(event.getAvgPrice())
                .setScore(event.getScore())
                .setComments(event.getComments())
                .setSold(event.getSold())
                .setHotScore(event.getHotScore())
                .setStatus(event.getStatus());
    }

    /**
     * 事件是否带齐"新建一条搜索文档"所必需的整行字段。
     *
     * 判据用 name/typeId/address：这三列在 shop_search_doc 里都是 NOT NULL，缺任一个都无法插入。
     */
    private boolean hasFullRow(ShopDocChangedEvent event) {
        return event.getName() != null && event.getTypeId() != null && event.getAddress() != null;
    }

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
