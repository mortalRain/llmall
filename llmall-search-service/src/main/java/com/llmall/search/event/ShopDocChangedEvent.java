package com.llmall.search.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：门店搜索文档变更（整行快照），路由键 {@code shop.hot.changed}。
 *
 * 事件契约本应放在 llmall-api（该模块已冻结），故生产端与消费端各持一份字段名、类型完全一致的镜像类：
 * 消费端靠 Spring AMQP 的 {@code Jackson2JsonMessageConverter} 默认 INFERRED 优先级，
 * 按监听方法参数类型反序列化，不会去加载 {@code __TypeId__} 头里的生产端类名。
 *
 * 【注意】JSON 字段名 + 类型才是真正的契约：改字段名必须两侧同步改（只改一边的症状是搜索视图被静默刷成 null）；
 * 若把 typeMapper 调成 TYPE_ID 优先级，这条链路会立刻断。
 *
 * 事件带整行而不是只带热点字段，消费侧才能整行 upsert：sold、status、名称地址坐标都跟着事件走。
 */
@Data
@Accessors(chain = true)
public class ShopDocChangedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：门店 id + 事件毫秒时间戳（写进本域 consume_record，供门店域对账查询） */
    private String bizId;

    private Long shopId;

    private Long typeId;

    private String typeName;

    private String name;

    private String area;

    private String address;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 人均价格（分）：生产端已做过「元 → 分」换算，消费端不要重复乘 100 */
    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    /** 评分 ×10 */
    private Integer score;

    private Double hotScore;

    /** 1 营业中 2 休息中 3 已下线 */
    private Integer status;

    /** 发生时间（epoch 毫秒）：用于乱序事件的版本比较 */
    private Long occurredAt;
}
