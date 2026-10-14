package com.llmall.shop.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：门店搜索文档变更（整行快照）。路由键仍是 {@code shop.hot.changed}。
 * 带整行快照是为了让搜索域一次 upsert 完整文档，否则 {@code shop_search_doc.sold}
 * 只能停留在上一次全量重建的值上。
 *
 * 【注意】契约本应在 llmall-api，但当前不允许修改 llmall-api / llmall-common，故生产端把契约放在
 * 本模块、消费端持有一份字段完全一致的镜像类（{@code com.llmall.search.event.ShopDocChangedEvent}）。
 * 互通依赖 Spring AMQP {@code Jackson2JsonMessageConverter} 默认的 {@code TypePrecedence = INFERRED}
 * （按监听方法参数类型反序列化），也就是说 JSON 字段名 + 字段类型才是真正的契约；
 * 若把 typeMapper 改成 TYPE_ID 优先级，这条链路会立刻断。
 */
@Data
@Accessors(chain = true)
public class ShopDocChangedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：门店 id + 事件毫秒时间戳（搜索域写 consume_record 与对账都用它） */
    private String bizId;

    private Long shopId;

    private Long typeId;

    /** 类型名（冗余存一份：检索不再关联字典表；搜索域新增文档时需要有值） */
    private String typeName;

    private String name;

    private String area;

    private String address;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 人均价格（分）：{@code shop.avg_price} 库里是「元」，事件与搜索读模型统一带「分」，不做二次换算 */
    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    /** 评分 ×10 */
    private Integer score;

    private Double hotScore;

    /** 1 营业中 2 休息中 3 已下线 */
    private Integer status;

    /** 发生时间（epoch 毫秒，跨服务不依赖双方时间序列化配置），搜索域用它做乱序事件的版本比较 */
    private Long occurredAt;
}
