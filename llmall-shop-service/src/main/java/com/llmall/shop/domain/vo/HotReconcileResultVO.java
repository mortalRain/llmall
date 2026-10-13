package com.llmall.shop.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 门店热度事件对账结果（运维接口 POST /shops/hot/reconcile 的返回）。
 *
 * 为什么把三个计数分开返回、而不是只给一个"是否一致"：
 * 对账的关键在于**严格区分"未处理"与"不知道"**（见 ShopSearchReconcileTask 的类注释）。
 * 把 unknown 混进任何一边都会掩盖缺口：混进"已处理"→ 一致性问题被伪装成正常；
 * 混进"未处理"→ 下游一挂就补偿风暴。所以三个数字必须都露出来。
 */
@Data
@Accessors(chain = true)
public class HotReconcileResultVO {

    /** 本轮检查的事件条数 */
    private int checked;

    /** 下游确认已处理（消息已置为 DONE，不再重复检查） */
    private int consumed;

    /** 下游明确未处理 → 已补发 */
    private int missing;

    /** 查询失败/下游不可用 → 不下结论，等下一轮（不计入已处理） */
    private int unknown;
}
