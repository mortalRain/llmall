package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 支付单状态查询结果 —— 一个显式的**三态**结果：已支付 / 未支付 / 未知。
 *
 * <h3>为什么不能让查询接口用 null 表示"没支付"</h3>
 * 交易域的超时关单逻辑要回答"这笔订单到底付没付"。当 llmall-pay-service 抖动或没启动时，
 * Feign 降级返回 null，调用方看到 null 会顺着写成"查不到 = 没支付 = 关单退库存"——
 * 于是用户钱已经扣了、订单却被关掉、库存被退回：**把"未知"翻译成了业务结论**。
 * 这是所有降级实现最危险的错误：降级能表达的只有"我没查到"，
 * 绝不能表达"业务上不存在/没有发生"。两者在数据上长得一模一样（都是空），
 * 在业务上的后果却完全相反。
 *
 * <h3>三态怎么用</h3>
 * <ul>
 *   <li>{@code determined=true, found=true, payStatus=3} → 已支付</li>
 *   <li>{@code determined=true, found=true, payStatus=1/2} → 确认未支付（可以关单）</li>
 *   <li>{@code determined=true, found=false} → 确认"没有支付单"（可以关单）</li>
 *   <li>{@code determined=false} → **状态未知**：调用方不得据此做任何关单/退款决定，
 *       只能重试或告警，让订单保持原状等下一次判断</li>
 * </ul>
 */
@Data
@Schema(description = "支付单状态查询结果（三态：已支付/未支付/未知）")
public class PayOrderStatusDTO {

    /**
     * 支付单状态：0 未提交、1 待支付、2 已关闭、3 支付成功；未知时为 null。
     *
     * <p>{@code @JsonAlias("status")}：兼容"支付服务尚未跟着升级"的中间状态 ——
     * 旧版接口返回的是支付单本体（字段名 status）。加上别名后，
     * 旧版返回体里的 status 也能被识别为"支付服务确实回答了"，避免滚动发布期间
     * 交易域把**所有**订单都判成"未知"而永不关单。
     */
    @JsonAlias("status")
    @Schema(description = "支付单状态：0 未提交，1 待支付，2 已关闭，3 支付成功；未知时为 null")
    private Integer payStatus;

    /** 是否确认存在支付单（用户从没发起过支付时就是 false，这本身是一个确定的业务结论） */
    @Schema(description = "是否确认存在支付单")
    private Boolean found;

    /**
     * 状态是否**已确定**。false 表示查询降级/失败，此时 payStatus 与 found 都不可信，
     * 调用方必须按"未知"处理。
     */
    @Schema(description = "状态是否已确定（false=降级或查询失败，不得据此做业务判断）")
    private Boolean determined;

    /** 未知的原因，只用于日志与告警，不面向用户展示 */
    @Schema(description = "状态未知的原因（仅用于日志）")
    private String unknownReason;

    /**
     * 状态是否已确定。
     *
     * <p>判据两条满足其一：显式的 determined=true，或**返回体里带着支付单状态**。
     * 后者是兼容旧版支付服务返回体（字段名 status，经 {@code @JsonAlias} 映射过来）：
     * 能拿到状态就说明这次查询真的成功过，可以按业务结论使用；
     * 拿不到任何状态（降级、空响应）才算"未知"。
     * 反过来"未知"绝不允许被调用方读成"未支付" —— 这是本类存在的全部意义。
     */
    public boolean isDetermined() {
        return Boolean.TRUE.equals(determined) || payStatus != null;
    }

    /** 是否确认存在支付单：显式 found，或返回体里带了状态（有状态必然有支付单） */
    public boolean isFound() {
        return Boolean.TRUE.equals(found) || payStatus != null;
    }

    /** 查询成功且支付单存在 */
    public static PayOrderStatusDTO of(Integer payStatus) {
        PayOrderStatusDTO dto = new PayOrderStatusDTO();
        dto.setPayStatus(payStatus);
        dto.setFound(true);
        dto.setDetermined(true);
        return dto;
    }

    /** 查询成功，但确实没有这笔业务的支付单（用户从没发起过支付） */
    public static PayOrderStatusDTO notFound() {
        PayOrderStatusDTO dto = new PayOrderStatusDTO();
        dto.setPayStatus(null);
        dto.setFound(false);
        dto.setDetermined(true);
        return dto;
    }

    /** 查询降级/失败：状态未知，调用方必须按"未知"处理，不得关单 */
    public static PayOrderStatusDTO unknown(String reason) {
        PayOrderStatusDTO dto = new PayOrderStatusDTO();
        dto.setPayStatus(null);
        dto.setFound(false);
        dto.setDetermined(false);
        dto.setUnknownReason(reason);
        return dto;
    }
}
