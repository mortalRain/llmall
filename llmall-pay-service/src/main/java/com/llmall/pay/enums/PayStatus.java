package com.llmall.pay.enums;

import lombok.Getter;

/**
 * 支付单状态字典，取值与 pay_order.status 一一对应。
 *
 * 【取值与调用方处理】
 * 0 未提交 / 1 待支付：中间态，可以继续推进（余额支付要求从 1 起步，条件更新后置为 4 支付中）；
 * 4 支付中：本次扣款尝试已取得闸门，成功推进为 3，失败归位为 1；
 * 2 已关闭：终态，由交易域退款确认后回写；
 * 3 支付成功：支付域内不再前进，但退款确认后仍会被回写成 2，因此它不是不可逆的终态。
 * 所有状态变更都是"带上允许的源状态"的条件更新，禁止查到就 set。
 *
 * 【为什么需要 4 支付中】此前用"把 status 更新成同一个值"来抢闸门，正确性依赖 JDBC 返回匹配行数；
 * 一旦驱动改成返回实际变更行数，该更新恒返回 0，所有支付都会被误判为"已支付或已关闭"。
 * 显式中间态让更新值一定发生变化，同时把"有人正在支付"表达在账本上。
 *
 * 【注意】本字典没有"未知"态：跨服务扣款结果不可知时，支付单停在 4 并写
 * pay_order.result_code=UNCERTAIN，由对账任务取出；禁止把"状态=1"读成"确定未支付"——
 * 未知不等于未支付，按未支付放行会造成重复扣款或漏单。
 *
 * 【注意】TRADE_FINISHED 与 TRADE_SUCCESS 同为 3，equalsValue 无法区分两者：新代码一律用 TRADE_SUCCESS。
 */
@Getter
public enum PayStatus {
    NOT_COMMIT(0, "未提交"),
    WAIT_BUYER_PAY(1, "待支付"),
    TRADE_CLOSED(2, "已关闭"),
    TRADE_SUCCESS(3, "支付成功"),
    PAYING(4, "支付中"),
    TRADE_FINISHED(3, "支付成功"),
    ;
    private final int value;
    private final String desc;

    PayStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public boolean equalsValue(Integer value){
        if (value == null) {
            return false;
        }
        return getValue() == value;
    }
}