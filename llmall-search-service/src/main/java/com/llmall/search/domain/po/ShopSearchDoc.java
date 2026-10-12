package com.llmall.search.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 门店搜索视图（本地读模型）。
 *
 * 为什么需要它：搜索域不允许直接查 llmall-shop 库（数据所有权），也不应该每次检索都跨服务调用
 * （N+1 与延迟问题）。所以用「**事件驱动构建本地视图**」：
 *   - 门店热度变更时由 MQ 事件增量更新（热点数据）；
 *   - 首次部署或视图损坏时可通过 ShopClient 全量重建。
 * 这就是 CQRS 里"写模型（门店域）/ 读模型（搜索域）分离"的最小落地。
 *
 * 若日后引入 ES，本表结构可直接作为索引映射使用，业务代码只需替换检索实现。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop_search_doc")
public class ShopSearchDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "shop_id", type = IdType.INPUT)
    private Long shopId;

    private String name;

    private Long typeId;

    /** 类型名称（冗余，避免检索时再关联） */
    private String typeName;

    private String area;

    private String address;

    /** 经度（ES 索引里拼成 geo_point 做"附近"排序；MySQL 检索不参与排序，见 sql/merged/41-search-geo.sql） */
    private Double x;

    /** 纬度 */
    private Double y;

    private Long avgPrice;

    /** 评分 ×10 */
    private Integer score;

    private Integer comments;

    private Integer sold;

    /** 热度分（排序主键） */
    private Double hotScore;

    /** 1 营业中 3 已下线 */
    private Integer status;

    /** 最近一次事件时间：用于乱序消息的版本比较 */
    private LocalDateTime eventTime;

    private LocalDateTime updateTime;
}
