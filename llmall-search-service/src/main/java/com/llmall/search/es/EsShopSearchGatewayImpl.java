package com.llmall.search.es;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.domain.PageDTO;
import com.llmall.search.domain.po.ShopSearchDoc;
import com.llmall.search.mapper.ShopSearchDocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * ES 网关实现（Elasticsearch 7.17.x + spring-data-elasticsearch 4.4）。
 *
 * 排序设计（**这是搜索质量的核心，也是与 MySQL 版最大的差别**）：
 *   基础相关性：multiMatch（name^3 / typeName^2 / area^2 / address），IK 分词，BEST_FIELDS 策略；
 *   综合打分（function_score，scoreMode=SUM，boostMode=SUM）：
 *     ① hotScore 用 fieldValueFactor + LOG1P —— 直接线性用热度会让头部门店永远霸榜，
 *        取对数后"热度 1000 与 100"的差距被压缩，长尾优质门店仍有曝光机会；
 *     ② eventTime 用 gauss 衰减（origin=now, scale=30d）—— 新近被评价的门店加权，
 *        抑制"老店吃老本"，这也是设计文档里说的"时间衰减抑制刷量"；
 *     ③ score（评分）×0.5、comments（评价数）LOG1P×0.3 —— 质量信号，权重低于热度与新鲜度。
 *   距离场景：给了 x/y 就按 geo_distance 升序（"附近"语义优先于综合排序），
 *             并把距离回填到 ShopDTO.distance（前端要显示"距您 1.2km"）。
 *
 * 注意：无关键词时 bool 只剩 filter 子句，Lucene 打分为 0；
 *      若不加 matchAllQuery 兜底，boostMode(SUM) 之前的老写法（MULTIPLY）会把总分乘成 0，
 *      排序会退化成"随机顺序" —— 这是 function_score 最常踩的坑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(ElasticsearchOperations.class)
public class EsShopSearchGatewayImpl implements EsShopSearchGateway {

    private static final int STATUS_OPEN = 1;
    private static final int REINDEX_PAGE_SIZE = 200;
    private static final int REINDEX_MAX_PAGES = 200;

    private final ElasticsearchOperations operations;
    private final ShopSearchDocMapper shopSearchDocMapper;
    private final ShopSearchEngineProperties properties;

    @Override
    public boolean enabled() {
        return properties.useEs();
    }

    @Override
    public boolean healthy() {
        try {
            count();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long count() {
        return operations.count(
                new NativeSearchQueryBuilder().withQuery(QueryBuilders.matchAllQuery()).build(),
                ShopSearchDocument.class);
    }

    @Override
    public boolean createIndexIfAbsent() {
        IndexOperations ops = operations.indexOps(ShopSearchDocument.class);
        if (ops.exists()) {
            return false;
        }
        ops.create();
        ops.putMapping(ops.createMapping());
        ops.refresh();
        log.info("[ES] 索引 shop_search 已创建（IK 分词 + geo_point）");
        return true;
    }

    @Override
    public PageDTO<ShopDTO> search(String keyword, Long typeId, int page, int size, Double x, Double y) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("status", STATUS_OPEN));
        if (typeId != null) {
            bool.filter(QueryBuilders.termQuery("typeId", typeId));
        }
        String kw = StrUtil.trimToNull(keyword);
        if (kw != null) {
            // 字段权重必须用 field(field, boost) 显式给，**不能**写成 "name^3" 这种字符串：
            // HLRC 会把它当成"字段名里带 ^"，序列化后服务端解析出 "2^1.0" 这类非法数字，
            // 报 number_format_exception（实测踩过，日志里是 x_content_parse_exception: failed to parse field [must]）。
            bool.must(QueryBuilders.multiMatchQuery(kw)
                    .field("name", 3f)
                    .field("typeName", 2f)
                    .field("area", 2f)
                    .field("address", 1f)
                    .type(MultiMatchQueryBuilder.Type.BEST_FIELDS));
        } else {
            bool.must(QueryBuilders.matchAllQuery());
        }

        FunctionScoreQueryBuilder.FilterFunctionBuilder[] functions = {
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("hotScore")
                                .factor((float) properties.getEs().getHotScoreFactor())
                                .modifier(FieldValueFactorFunction.Modifier.LOG1P)
                                .missing(0D)),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        // 时间衰减的字段是 epoch 毫秒（eventTimestamp），不是 LocalDateTime —— 见 ShopSearchDocument 注释
                        ScoreFunctionBuilders.gaussDecayFunction("eventTimestamp", "now",
                                properties.getEs().getTimeDecayScale())),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("score")
                                .factor((float) properties.getEs().getScoreBoost())
                                .modifier(FieldValueFactorFunction.Modifier.NONE)
                                .missing(0D)),
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        ScoreFunctionBuilders.fieldValueFactorFunction("comments")
                                .factor((float) properties.getEs().getCommentsBoost())
                                .modifier(FieldValueFactorFunction.Modifier.LOG1P)
                                .missing(0D))
        };
        QueryBuilder query = new FunctionScoreQueryBuilder(bool, functions)
                .scoreMode(FunctionScoreQuery.ScoreMode.SUM)
                .boostMode(CombineFunction.SUM);

        boolean nearby = x != null && y != null;
        NativeSearchQueryBuilder builder = new NativeSearchQueryBuilder()
                .withQuery(query)
                .withPageable(PageRequest.of(Math.max(0, page - 1), size))
                // 默认 ES 只精确统计到 1 万条，分页总数会失真；搜索页要显示总数，必须打开
                .withTrackTotalHits(true);
        if (nearby) {
            // 注意参数顺序：ES 的 GeoPoint 是 (纬度 lat, 经度 lon)，
            // 而库里 x=经度、y=纬度，别写反（写反的后果是"附近的店"跑到几千公里外）
            builder.withSort(SortBuilders.geoDistanceSort("location", y, x)
                    .unit(DistanceUnit.KILOMETERS)
                    .order(SortOrder.ASC));
        } else {
            builder.withSort(SortBuilders.scoreSort().order(SortOrder.DESC));
        }

        SearchHits<ShopSearchDocument> hits = operations.search(builder.build(), ShopSearchDocument.class);
        List<ShopDTO> list = new ArrayList<>(hits.getSearchHits().size());
        for (SearchHit<ShopSearchDocument> hit : hits.getSearchHits()) {
            ShopDTO dto = toShopDTO(hit.getContent());
            if (nearby && !hit.getSortValues().isEmpty() && hit.getSortValues().get(0) instanceof Number) {
                dto.setDistance(((Number) hit.getSortValues().get(0)).doubleValue());
            }
            list.add(dto);
        }
        long total = hits.getTotalHits();
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageDTO<>(total, pages, list);
    }

    @Override
    public void saveDocument(ShopSearchDocument document) {
        if (document == null || document.getShopId() == null) {
            return;
        }
        operations.save(document);
    }

    @Override
    public int saveDocuments(Collection<ShopSearchDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        operations.save(documents);
        return documents.size();
    }

    @Override
    public int reindexFromReadModel() {
        int page = 1;
        int total = 0;
        while (page <= REINDEX_MAX_PAGES) {
            Page<ShopSearchDoc> result = shopSearchDocMapper.selectPage(
                    new Page<>(page, REINDEX_PAGE_SIZE),
                    new LambdaQueryWrapper<ShopSearchDoc>().orderByAsc(ShopSearchDoc::getShopId));
            if (result == null || result.getRecords() == null || result.getRecords().isEmpty()) {
                break;
            }
            List<ShopSearchDocument> docs = new ArrayList<>(result.getRecords().size());
            for (ShopSearchDoc doc : result.getRecords()) {
                docs.add(toDocument(doc));
            }
            total += saveDocuments(docs);
            if (page >= result.getPages()) {
                break;
            }
            page++;
        }
        // 强制刷新：ES 默认 1s 才刷新一次，不刷新的话"刚调完重建就查"会读到 0 条。
        // 实测踩过：POST /search/index/rebuild 写入 30 条后立刻 GET /search/engine，读到 esDocs=0。
        // 这里只刷新一次（重建是低频运维动作），不影响热点事件同步的性能。
        try {
            operations.indexOps(ShopSearchDocument.class).refresh();
        } catch (Exception e) {
            log.warn("[ES] 重建后 refresh 失败（数据已写入，稍后即可见）: {}", e.getMessage());
        }
        log.info("[ES] 全量重建索引完成，共写入 {} 条", total);
        return total;
    }

    // ==================== 模型转换 ====================

    /** MySQL 读模型 → ES 文档。x/y 拼成 geo_point（"纬度,经度"） */
    public static ShopSearchDocument toDocument(ShopSearchDoc doc) {
        ShopSearchDocument target = new ShopSearchDocument()
                .setShopId(doc.getShopId())
                .setName(doc.getName())
                .setTypeId(doc.getTypeId())
                .setTypeName(doc.getTypeName())
                .setArea(doc.getArea())
                .setAddress(doc.getAddress())
                .setAvgPrice(doc.getAvgPrice())
                .setScore(doc.getScore())
                .setComments(doc.getComments())
                .setSold(doc.getSold())
                .setHotScore(doc.getHotScore() == null ? 0D : doc.getHotScore())
                .setStatus(doc.getStatus())
                // 时间统一写 epoch 毫秒（mapping format=epoch_millis）：
                // 用 LocalDateTime 会被 SDE 4.4 截断成 yyyy-MM-dd，且读回时转换报错 —— 见 ShopSearchDocument 注释
                .setEventTimestamp(doc.getEventTime() == null ? null
                        : doc.getEventTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        if (doc.getX() != null && doc.getY() != null) {
            target.setLocation(doc.getY() + "," + doc.getX());
        }
        return target;
    }

    private ShopDTO toShopDTO(ShopSearchDocument doc) {
        ShopDTO dto = new ShopDTO();
        dto.setId(doc.getShopId());
        dto.setName(doc.getName());
        dto.setTypeId(doc.getTypeId());
        dto.setTypeName(doc.getTypeName());
        dto.setArea(doc.getArea());
        dto.setAddress(doc.getAddress());
        dto.setAvgPrice(doc.getAvgPrice());
        dto.setScore(doc.getScore());
        dto.setComments(doc.getComments());
        dto.setSold(doc.getSold());
        dto.setHotScore(doc.getHotScore());
        dto.setStatus(doc.getStatus());
        return dto;
    }
}
