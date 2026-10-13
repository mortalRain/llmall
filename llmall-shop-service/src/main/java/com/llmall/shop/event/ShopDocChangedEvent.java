package com.llmall.shop.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 事件：门店搜索文档变更（整行快照）。路由键仍是 {@code shop.hot.changed}。
 *
 * <h3>为什么不再用 llmall-api 的 ShopHotChangedEvent</h3>
 * 审计 B4 的第二条要求是"事件体带 sold/status，让搜索侧整行 upsert"。原事件体只有
 * hotScore/comments/score 三个热点字段，搜索域拿到它只能"局部更新三列"，
 * 于是 {@code shop_search_doc.sold} 永远停在**上一次全量重建**的值上（销量永久冻结）。
 * 把整行快照带上，搜索域就能一次 upsert 完整文档，销量不再是"只重建才更新"。
 *
 * <h3>为什么这个类在 llmall-shop-service 里、搜索域还有一份同名字段的镜像</h3>
 * 事件契约本该放在 llmall-api（跨服务共享模块）。本次改动**不允许修改 llmall-api / llmall-common**，
 * 所以生产端把契约放在自己的模块里，消费端持有一份**字段完全一致**的镜像类
 * （{@code com.llmall.search.event.ShopDocChangedEvent}）。
 *
 * 这样能跑通是因为 Spring AMQP 的 {@code Jackson2JsonMessageConverter} 默认
 * {@code TypePrecedence = INFERRED}：消费端按**监听方法参数类型**反序列化，
 * 不去加载 {@code __TypeId__} 头里的类名（否则会 ClassNotFound）。也就是说
 * **JSON 字段名 + 字段类型才是真正的契约**，两边的类各自独立存在也不影响互通。
 * ⚠️ 如果哪天有人把 typeMapper 改成 TYPE_ID 优先级，这条链路会立刻断 —— 改动时务必连带验证本事件。
 *
 * <h3>字段口径（踩过的坑，别照抄直觉）</h3>
 *  - {@code avgPrice} 单位是**分**：{@code shop.avg_price} 库里存的是「元」，
 *    而搜索读模型 {@code shop_search_doc.avg_price} 与 DTO 契约一列都是「分」
 *    （见 ShopServiceImpl#toDTO 的 ×100）。事件直接带"分"，搜索域不做二次换算，
 *    否则会出现"门店卡片人均 ¥0.8"那类静默错数。
 *  - {@code score} 是**评分 ×10**（45 = 4.5 分），与 shop 表、搜索读模型一致。
 *  - {@code x}/{@code y} 分别是**经度/纬度**（ES 的 geo_point 要拼成"纬度,经度"，方向别写反）。
 *  - {@code occurredAt} 用 epoch 毫秒（不用 LocalDateTime）：跨服务消息不依赖双方时间序列化配置。
 */
@Data
@Accessors(chain = true)
public class ShopDocChangedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 幂等键：门店 id + 事件毫秒时间戳（搜索域写入 consume_record 用的就是它，对账也靠它） */
    private String bizId;

    private Long shopId;

    private Long typeId;

    /** 类型名（冗余，检索时不再关联字典表；搜索域新增文档时需要有值） */
    private String typeName;

    private String name;

    private String area;

    private String address;

    /** 经度 */
    private Double x;

    /** 纬度 */
    private Double y;

    /** 人均价格（分） */
    private Long avgPrice;

    /** 销量（本次修复的重点：让搜索读模型的 sold 随事件更新，而不是只等全量重建） */
    private Integer sold;

    private Integer comments;

    /** 评分 ×10 */
    private Integer score;

    private Double hotScore;

    /** 1 营业中 2 休息中 3 已下线 */
    private Integer status;

    /** 发生时间（epoch 毫秒）：搜索域用它做乱序事件的版本比较 */
    private Long occurredAt;
}
