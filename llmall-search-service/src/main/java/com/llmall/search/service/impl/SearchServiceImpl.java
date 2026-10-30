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
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.tx.TxAfterCommit;
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
import com.llmall.search.support.SearchPagingPolicy;
import com.llmall.search.support.EmptyIndexFallbackProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
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
 * 搜索域核心实现：检索（双引擎）、热词与建议、搜索历史、读模型维护、索引重建与对账。
 *
 * 【核心职责】
 * 1. 检索：优先走 ES（IK 中文分词、多字段相关性、function_score、geo 距离）；
 *    MySQL 读模型 {@code shop_search_doc} 既是权威数据，也是 ES 不可用时的回落通道。
 * 2. 热词两段式：运行时 ZINCRBY 累加到 Redis ZSet（读时实时取 Top N），
 *    定时任务批量落库到 {@code search_hot_word}，避免每次搜索都写 MySQL。
 * 3. 读模型维护：消费 {@code shop.hot.changed} 事件做整行 upsert，并提供全量重建。
 * 4. 对账：按 {@code consume_record} 回答门店域"这条事件处理了没有"。
 *
 * 【使用场景】
 * HTTP 入口是 {@code SearchController}；{@link #applyShopHotChanged} 由 MQ 监听器
 * {@code ShopHotChangedListener} 在事务内调用；{@link #syncHotWordsToDb} 由定时任务
 * {@code HotWordSyncTask} 调用；{@link #isShopHotChangedConsumed} 由门店域对账任务经 Feign 调用。
 *
 * 【关键设计】
 * 1. 三态引擎（{@code llmall.search.engine}）：{@code es} = 只用 ES，检索抛错直接向上抛
 *    （刻意不回落，保证"纯 ES"的压测数据干净）；{@code mysql} = 只用 MySQL 读模型（等价于引入 ES 之前的行为）；
 *    {@code auto}（默认）= 优先 ES，ES 抛错则 warn 并回落 MySQL，接口对前端仍可用。
 * 2. 权威数据在 MySQL：ES 只是加速副本，读模型永远是可以重建的唯一真相。
 * 3. 幂等闸门不在本类：事件幂等由 {@code consume_record} 的唯一键
 *    {@code (biz_type, biz_id, consumer)} 裁决，见 {@code ShopHotChangedListener}。
 *
 * ES按版本替换完整文档：提交前读取当前完整行，提交后发送，不能用局部事件直接替换索引。
 * 同步仍是best-effort，consume_record不证明ES已写入；engineStatus数量相等也不代表内容一致。
 * 失败需受控重建等修复，本轮没有为ES增加持久重试账本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    /** 门店状态：营业中。检索只返回该状态，打烊/下线门店不进搜索结果 */
    private static final int STATUS_OPEN = 1;
    /** 全量重建时向门店域拉取的每页条数 */
    private static final int REBUILD_PAGE_SIZE = 100;
    /** 全量重建的页数上限，防止对端分页异常时死循环 */
    private static final int REBUILD_MAX_PAGES = 100;
    /** Only global index/read-model evidence is cached; search results are never cached. */
    private final EmptyIndexFallbackProbe emptyIndexProbe = new EmptyIndexFallbackProbe(Duration.ofSeconds(10));

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
    private final BizMetrics metrics;

    /** 门店检索（无坐标）：委托 {@link #doSearch}，x/y 传 null 即走综合排序 */
    @Override
    public PageDTO<ShopDTO> search(String keyword, Long typeId, Integer page, Integer size, Long userId) {
        return doSearch(keyword, typeId, page, size, null, null, userId);
    }

    /** 附近检索：x/y 同时非空时 ES 侧改按距离升序，MySQL 回落路径忽略坐标只做普通检索 */
    @Override
    public PageDTO<ShopDTO> searchNearby(String keyword, Long typeId, Integer page, Integer size,
                                         Double x, Double y, Long userId) {
        return doSearch(keyword, typeId, page, size, x, y, userId);
    }

    /**
     * 检索主流程：先试 ES，再视引擎配置决定"回落 MySQL"还是"直接向上抛"。
     *
     * 【设计意图】
     * 检索是读接口，ES 抖动不应该让整个搜索页打不开；但压测/对照场景又需要一份"纯 ES"的数据，
     * 因此把选择权交给 {@code llmall.search.engine}：{@code auto} 回落保证可用性，
     * {@code es} 不回落保证观测口径纯净。回落点只记 warn 不埋点，说明它本身就是"异常路径"。
     *
     * 【边界约束】
     * 1. 每页条数上限 50，比 {@code PageQuery} 的全局上限 100 更严 —— 放宽会让一次检索
     *    从 ES/MySQL 拉走更多行，检索 QPS 高时放大对两边的压力。
     * 2. MySQL 回落的排序口径必须保持"hotScore → comments"：与 ES 的 function_score
     *    不追求数值一致，但"热门店在前"这一相对次序要一致，否则换引擎后前端看到的名次会突变。
     * 3. 关键词命中范围必须包含 {@code typeName}：首页分类 chip 传的就是类型名（"美食""KTV"），
     *    漏掉它会让点击分类后搜不到任何门店。
     *
     * @param keyword 关键词，可空（为空即按热度/类型浏览）；前后空白会被 trim，全空白视同为空
     * @param typeId  门店类型 id，可空；非空时精确过滤
     * @param page    页码，<=0 或 null 收敛为第 1 页
     * @param size    每页条数，非法值取默认 10，上限 50
     * @param x       经度，可空；仅与 y 同时给出时生效（"附近"场景）
     * @param y       纬度，可空
     * @param userId  当前登录用户 id，可空（未登录走匿名检索，只是不记搜索历史）
     * @return 分页结果；total 来自 ES 的精确总数或 MySQL 的 count，list 不会为 null
     * @throws IllegalStateException engine=es 时 ES 检索失败 —— 刻意不回落，调用方需自行降级或提示
     */
    private PageDTO<ShopDTO> doSearch(String keyword, Long typeId, Integer page, Integer size,
                                      Double x, Double y, Long userId) {
        // 检索接口的单页上限（50）刻意比 PageQuery 的全局上限更严，不要放宽
        int current = PageQuery.normalizePageNo(page);
        int pageSize = PageQuery.normalizeSize(size, 10, 50);
        String kw = StrUtil.trimToNull(keyword);
        // 深分页在入口拒绝：ES 超过 1 万条会抛错，auto 模式会把该错误误判成"ES 不可用"回落
        if (SearchPagingPolicy.exceedsWindow(current, pageSize)) {
            throw new BadRequestException("最多支持查看前 " + SearchPagingPolicy.MAX_RESULT_WINDOW
                    + " 条结果，请用筛选条件缩小范围");
        }

        if (esGateway.enabled()) {
            try {
                PageDTO<ShopDTO> result = esGateway.search(kw, typeId, current, pageSize, x, y);
                // 【空索引降级】ES 对"索引存在但没有文档"的查询返回 200 + 0 命中，不会抛异常 ——
                // 于是下面 catch 里的回落分支形同虚设：新环境建了索引却忘了灌数时，搜索页静默返回空列表，
                // 既不回落 MySQL、也不打 WARN，只有 /search/engine 的 esDocs=0 能看出来。
                // 这里补一道"命中 0 条"的判定：只有确认为"索引里一条文档都没有、而 MySQL 读模型有数据"
                // 才回落。单纯的"这个关键词没有门店命中"仍然照常返回空结果，不能被误判成故障。
                if (isEmptyResult(result) && engineProperties.fallbackToMysql() && isEmptyIndexButReadModelNotEmpty()) {
                    log.warn("[ES] 索引为空但读模型非空（esDocs=0），该次检索回落 MySQL 读模型: keyword={}", kw);
                    metrics.increment("llmall.search.fallback", "reason", "empty-index");
                }
                else {
                    // A real hit proves the global index is nonempty, including recovery after a rebuild.
                    if (result != null && result.getList() != null && !result.getList().isEmpty()) {
                        emptyIndexProbe.observeNonEmpty();
                    }
                    recordSearch(kw, userId);
                    return result;
                }
            } catch (Exception e) {
                // engine=es 时刻意不回落：混入 MySQL 结果会让"纯 ES"的表现失真
                if (!engineProperties.fallbackToMysql()) {
                    throw new IllegalStateException("ES 检索失败且未开启回落: " + e.getMessage(), e);
                }
                log.warn("ES 检索失败，回落 MySQL 读模型: {}", e.getMessage());
                metrics.increment("llmall.search.fallback", "reason", "es-error");
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

    /**
     * 检索结果是否"一条都没有"。ES 无命中时 total 是 0；这里把 null 也一并视为空，
     * 避免上游某种序列化把 total 丢掉时降级判定直接失效。
     */
    private boolean isEmptyResult(PageDTO<ShopDTO> result) {
        return result == null || result.getTotal() == null || result.getTotal() == 0L;
    }

    /** Share one global probe per instance/TTL; a missing keyword alone is not an index outage. */
    private boolean isEmptyIndexButReadModelNotEmpty() {
        return emptyIndexProbe.shouldFallback(() -> {
            try {
                if (esGateway.count() != 0L) return false;
                Long docs = shopSearchDocMapper.selectCount(null);
                return docs != null && docs > 0L;
            } catch (Exception e) {
                // Preserve the existing failure policy: do not switch engines on an inconclusive probe.
                log.warn("[ES] 空索引降级判定失败，按不降级处理: {}", e.getMessage());
                return false;
            }
        });
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
        // varchar(64) counts Unicode characters, not UTF-16 code units. Keep the search itself unchanged.
        if (keyword.codePointCount(0, keyword.length()) > 64) {
            return;
        }
        try {
            historyMapper.insertIfAbsent(userId, keyword);
        } catch (DataAccessException e) {
            // History is optional: its outage must not turn a successful ES search into a retry/fallback.
            metrics.increment("llmall.search.history.failure", "reason", "database");
            log.warn("搜索历史写入失败，不影响检索: userId={}, failure={}", userId, e.getClass().getSimpleName());
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
        if (event == null || event.getShopId() == null) return;
        if (event.getOccurredAt() == null || event.getOccurredAt() <= 0) {
            throw new BadRequestException("门店变更事件缺少有效的毫秒发生时间");
        }
        LocalDateTime eventTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getOccurredAt()),
                ZoneId.systemDefault()).truncatedTo(ChronoUnit.MILLIS);
        ShopSearchDoc doc = new ShopSearchDoc().setShopId(event.getShopId()).setEventTime(eventTime);
        fillFromEvent(doc, event);
        // 此快照只判断是否需要初始化，不能用于版本裁决；外层RR可能早已建立旧快照。
        if (shopSearchDocMapper.selectById(event.getShopId()) == null) {
            if (!hasFullRow(event)) {
                ShopDTO shop = shopClient.queryShopById(event.getShopId());
                if (shop == null) {
                    log.warn("视图补全失败：门店不存在, shopId={}", event.getShopId());
                    return;
                }
                fillFromShop(doc, shop);
            }
            // 并发首次创建用duplicate-key no-op拿排他锁，不靠两个INSERT争抢后的异常收敛。
            shopSearchDocMapper.insertIfAbsent(doc);
        }
        ShopSearchDoc current = shopSearchDocMapper.selectForUpdate(event.getShopId());
        if (current == null) throw new IllegalStateException("搜索读模型初始化后不存在");
        if (current.getEventTime() == null || eventTime.isAfter(current.getEventTime())) {
            shopSearchDocMapper.updateById(doc);
        }
        // 相同时间保留首个已提交快照；迟到/重投也可用当前权威行修复ES。
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

    /** 提交前捕获完整当前行，提交后再写ES；回调不复用旧MyBatis会话查询。 */
    private void syncToEs(Long shopId) {
        if (shopId == null || !esGateway.enabled()) return;
        ShopSearchDoc current = shopSearchDocMapper.selectForUpdate(shopId);
        if (current == null) return;
        com.llmall.search.es.ShopSearchDocument snapshot = EsShopSearchGatewayImpl.toDocument(current);
        TxAfterCommit.run("sync search document id=" + shopId, () -> {
            try {
                esGateway.saveDocument(snapshot);
            } catch (Exception e) {
                log.warn("[ES] 文档同步失败（MySQL已提交，可重建修复）shopId={}, failure={}", shopId, e.getClass().getSimpleName());
            }
        });
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
