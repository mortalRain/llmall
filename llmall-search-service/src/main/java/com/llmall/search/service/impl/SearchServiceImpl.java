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
 * 所以每次落库之后都**重新读一次完整行**再写 ES，拿局部 doc 去写会把名称、地址、坐标全刷成 null ——
 * 这是接 ES 时最容易犯、也最难查的错误。（审计 B4 之后事件体本身就是整行快照，
 * 这条约束依然照旧执行：读回来的才是"落库后的权威行"，内存里的 doc 可能被 not_null 策略跳过某些字段。）
 *
 * 对账（审计 B4）：本域消费事件时会写 consume_record，门店域的对账任务据此判断
 * "这条 shop.hot.changed 到底处理了没有"（见 isShopHotChangedConsumed / GET /search/consume-status）。
 * 没有这个判据，"消息发出去了但下游没处理成功"的缺口永远查不出来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    private static final int STATUS_OPEN = 1;
    private static final int REBUILD_PAGE_SIZE = 100;
    /** 全量重建的页数上限保护，避免接口异常导致死循环 */
    private static final int REBUILD_MAX_PAGES = 100;

    /** 本域在 consume_record 里的消费者标识（服务名），门店域对账时按它查"我处理了没有" */
    private static final String CONSUMER = "llmall-search-service";

    private final ShopSearchDocMapper shopSearchDocMapper;
    private final SearchHotWordMapper hotWordMapper;
    private final SearchHistoryMapper historyMapper;
    /** 消费幂等记录（审计 B4）：既是重投的闸门，也是门店域对账要问的"处理了没有"的判据 */
    private final ConsumeRecordMapper consumeRecordMapper;
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
        // 两个治理动作，缺一不可（该 ZSet 的写入口是**匿名可访问**的搜索接口）：
        //  ① 给 key 设 TTL：不设的话这个 ZSet 会永久增长，且"热词"永远是历史累计值，
        //     看不出近期趋势；过期即从零开始，天然形成滚动窗口。
        //  ② 按排名截断：只保留 TopN。否则任何匿名用户都能靠脚本刷入海量随机关键词，
        //     把 ZSet 撑成内存炸弹（每个成员都要占内存，且没有上限）。
        redisTemplate.expire(HOT_WORD_KEY, HOT_WORD_TTL);
        Long size = redisTemplate.opsForZSet().zCard(HOT_WORD_KEY);
        if (size != null && size > HOT_WORD_MAX_SIZE) {
            // 移除排名在 TopN 之外的部分（下标从 0 开始，-HOT_WORD_MAX_SIZE-1 即保留前 N 个）
            redisTemplate.opsForZSet().removeRange(HOT_WORD_KEY, 0, -HOT_WORD_MAX_SIZE - 1);
        }
    }

    private void saveHistory(Long userId, String keyword) {
        // 依赖表上的唯一键 (user_id, keyword) 做去重：先查后插在并发下必然插入重复行
        // （两个请求同时查到"不存在"），而重复历史会让"最近搜索"出现两条一样的词。
        // 这里直接插入并吞掉唯一键冲突 —— 冲突本身就代表"已经有了"，无需任何处理。
        try {
            historyMapper.insert(new SearchHistory().setUserId(userId).setKeyword(keyword));
        } catch (DuplicateKeyException e) {
            // 已经搜过同一个词：忽略即可（日志降到 debug，避免正常路径刷 warn）
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

    /**
     * 消费门店文档变更事件 → 整行 upsert 本地读模型（+ 同步 ES）。
     *
     * <h3>审计 B4 改前的问题</h3>
     * 原实现只把 hotScore/comments/score 三列写进 doc（其余列"由重建任务维护"），
     * 于是 {@code shop_search_doc.sold} 永远停在上一次**全量重建**的值上：
     * 销量在涨、搜索结果里的销量不动（实测库里 shop.sold=4215 而 shop_search_doc.sold=4219）。
     *
     * <h3>改后</h3>
     * 事件体升级为整行快照，这里**整行 upsert**：名称/类型/商圈/地址/坐标/人均/评分/评价数/销量/状态
     * 全部由事件驱动更新，不再依赖重建。
     *
     * <h3>兼容与防坑</h3>
     *  - 老格式事件（改造前投递、仍在队列里的，只有热点字段）：新增文档时回源门店详情补全整行
     *    （shop_search_doc 的 name/address/type_id 都是 NOT NULL，拿部分字段插入会直接失败）；
     *    更新已有文档时依靠 MyBatis-Plus 的 update-strategy=not_null，null 字段被跳过，
     *    不会把已有值刷成空。
     *  - 防乱序仍按事件时间比较（MQ 不保证顺序，旧事件后到会把新数据覆盖回去）。
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
        // 【必须截断到秒，否则重投的事件会被下面的防乱序判断误判成"过期"而丢弃】
        // shop_search_doc.event_time 是 DATETIME（无小数秒），写入 .906 秒时 MySQL 会**四舍五入到下一秒**，
        // 读回来变成 .000 —— 于是同一条事件（同一个 occurredAt）第二次到达时，
        // "事件时间 .906" < "库里已被四舍五入成下一秒的 event_time" 成立，事件被当成过期事件丢掉。
        // 实测（场景 c）：对账补发同一条 bizId 后，search 日志出现
        //   「收到过期的热度事件，忽略: shopId=1, eventTime=2026-10-13T18:22:03.906 < 已处理 2026-10-13T18:22:04」，
        // 而 consume_record 已经写下 —— 对账因此会回答"已处理"，读模型却停在旧值上，
        // 这正是审计 B4 要消除的"静默缺口"。截断到秒后，写入值与读回值一致（都是 .000），
        // 重投不再被误伤；防乱序仍然有效（精度降到 1 秒，跨秒的乱序照旧拦截）。
        eventTime = eventTime.truncatedTo(ChronoUnit.SECONDS);

        ShopSearchDoc exist = shopSearchDocMapper.selectById(event.getShopId());
        // 防乱序：MQ 不保证顺序，若旧事件后到会把新数据覆盖回去（典型"数据回退"故障）
        if (exist != null && exist.getEventTime() != null && eventTime.isBefore(exist.getEventTime())) {
            log.info("收到过期的热度事件，忽略: shopId={}, eventTime={} < 已处理 {}",
                    event.getShopId(), eventTime, exist.getEventTime());
            return;
        }

        ShopSearchDoc doc = new ShopSearchDoc().setShopId(event.getShopId()).setEventTime(eventTime);
        fillFromEvent(doc, event);

        if (exist == null) {
            if (!hasFullRow(event)) {
                // 视图里还没有该门店，而且事件是"老格式"（只带热点字段）→ 回源门店详情补全整行，
                // 否则 NOT NULL 的 name/address/type_id 会让 insert 直接失败。
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
            // 整行 upsert：事件带的是完整行，所以这里不再"只更新热点列"；
            // null 字段由 update-strategy=not_null 跳过（老格式事件不会把已有值刷空）
            shopSearchDocMapper.updateById(doc);
            log.info("搜索视图更新: shopId={}, hotScore={}, sold={}, comments={}",
                    event.getShopId(), doc.getHotScore(), doc.getSold(), doc.getComments());
        }

        // 同步 ES：doc 现在就是完整行，但**仍然重新读一次**再写 ——
        // ES 的写是整文档替换，读回来的是"落库后的权威行"，比内存里的 doc 更可靠
        // （尤其 doc 里可能有被 not_null 策略跳过的 null 字段）。
        syncToEs(event.getShopId());
    }

    @Override
    public boolean isShopHotChangedConsumed(String bizId) {
        if (StrUtil.isBlank(bizId)) {
            return false;
        }
        // 判据 = 本域消费记录里有没有这一行。它与视图更新同事务写入（见 ShopHotChangedListener），
        // 所以"有行"必然意味着"视图真的更新成功了"，不会出现"记了账但没干活"。
        Long count = consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getBizType, MqConstants.BIZ_SHOP_HOT_CHANGED)
                .eq(ConsumeRecord::getBizId, bizId)
                .eq(ConsumeRecord::getConsumer, CONSUMER));
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

    /**
     * 事件 → 读模型：整行快照逐字段搬运。
     *
     * 注意 update 路径上 null 字段会被 MyBatis-Plus 的 update-strategy=not_null 跳过，
     * 所以这里即使原样搬 null 也不会把已有值刷空 —— 这正是"老格式事件（只有热点字段）
     * 落到已有文档上"仍然安全的原因。
     */
    private void fillFromEvent(ShopSearchDoc doc, ShopDocChangedEvent event) {
        doc.setName(event.getName())
                .setTypeId(event.getTypeId())
                .setTypeName(event.getTypeName())
                .setArea(event.getArea())
                .setAddress(event.getAddress())
                .setX(event.getX())
                .setY(event.getY())
                // 事件里已经是「分」，与读模型/前端契约同口径，这里**不要**再 ×100
                .setAvgPrice(event.getAvgPrice())
                .setScore(event.getScore())
                .setComments(event.getComments())
                .setSold(event.getSold())
                .setHotScore(event.getHotScore())
                .setStatus(event.getStatus());
    }

    /**
     * 事件是否带齐了"新建一条搜索文档"所必需的整行字段。
     *
     * 判据用 name/typeId/address：这三列在 shop_search_doc 里都是 NOT NULL，
     * 缺任何一个都无法插入；老格式事件（改造前只带 hotScore/comments/score）会走到回源补全分支。
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
