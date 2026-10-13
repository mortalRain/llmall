package com.llmall.search.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：门店搜索文档变更（整行快照），路由键 {@code shop.hot.changed}。
 *
 * <h3>为什么这里有一份"和生产端长得一样"的类</h3>
 * 生产端是 {@code com.llmall.shop.event.ShopDocChangedEvent}（llmall-shop-service 模块）。
 * 事件契约本该放在 llmall-api，但本次修复（审计 B4）不允许修改 llmall-api / llmall-common，
 * 所以两边各持一份**字段名与类型完全一致**的镜像类。
 *
 * 能这样跑通的技术前提：Spring AMQP 的 {@code Jackson2JsonMessageConverter} 默认
 * {@code TypePrecedence = INFERRED} —— 消费端按**监听方法参数类型**反序列化，
 * 不会去加载 {@code __TypeId__} 头里的生产端类名（否则会 ClassNotFound）。
 * 换句话说：**JSON 字段名 + 类型才是真正的契约**，类放在哪一侧不重要。
 * ⚠️ 因此改字段名 = 改契约：两边必须同步改（改一边的症状是搜索视图被静默刷成 null）。
 * 同理，若哪天把 typeMapper 调成 TYPE_ID 优先级，这条链路会立刻断。
 *
 * <h3>为什么事件要带整行</h3>
 * 搜索读模型 {@code shop_search_doc.sold} 原来只在"全量重建"时才更新（审计 B4 第二条），
 * 因为老事件体只有 hotScore/comments/score。带上整行后，消费侧可以**整行 upsert**：
 * sold、status、名称地址坐标都跟着事件走，销量不再永久冻结。
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

    /** 人均价格（分）：生产端已做过「元 → 分」换算，这里不要重复乘 100 */
    private Long avgPrice;

    /** 销量 */
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
