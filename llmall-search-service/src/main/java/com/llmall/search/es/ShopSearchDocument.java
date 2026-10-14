package com.llmall.search.es;

import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;

/**
 * ES 文档：门店检索索引 {@code shop_search}，与 MySQL 读模型 {@link com.llmall.search.domain.po.ShopSearchDoc}
 * 一比一对应，只多一个 {@code location}（MySQL 存 x/y 两个 double，ES 要用 geo_point 才能做距离排序）。
 *
 * 分词口径：索引时 {@code ik_max_word}（细粒度、覆盖率优先），检索时 {@code ik_smart}
 * （粗粒度，避免"望京小腰"被切碎后召回无关门店）。
 *
 * 【注意】SDE 4.4 的 {@code @Field} 没有 fields 属性，不能配多字段；日期不要用 LocalDateTime
 * （无显式 format 时会写成 yyyy-MM-dd，丢时分秒且反向读取抛异常），统一用 epoch 毫秒的 Long。
 */
@Data
@Accessors(chain = true)
@Document(indexName = "shop_search")
public class ShopSearchDocument {

    /** 门店 id（与 MySQL 读模型同主键，重复写即覆盖，天然幂等） */
    @Id
    private Long shopId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;

    @Field(type = FieldType.Long)
    private Long typeId;

    /** 类型名（"美食""KTV"这类分类词要能被搜到，故同样走 IK 分词） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String typeName;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String area;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String address;

    @Field(type = FieldType.Long)
    private Long avgPrice;

    /** 评分 ×10（45 = 4.5 分） */
    @Field(type = FieldType.Integer)
    private Integer score;

    @Field(type = FieldType.Integer)
    private Integer comments;

    @Field(type = FieldType.Integer)
    private Integer sold;

    /** 热度分：function_score 的主排序因子 */
    @Field(type = FieldType.Double)
    private Double hotScore;

    /** 1 营业中 2 休息中 3 已下线 */
    @Field(type = FieldType.Integer)
    private Integer status;

    /** 经纬度：ES geo_point 的字符串写法，"纬度,经度" 顺序，由 x/y 拼成 */
    @GeoPointField
    private String location;

    /** 最近一次事件时间（epoch 毫秒）：时间衰减打分的锚点 */
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis)
    private Long eventTimestamp;
}
