package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 支付单状态查询结果：显式的三态结果 —— 已支付 / 未支付 / 未知。
 *
 * 【注意】只有 determined=true 时 found 与 payStatus 才可信，此时 found=false 或 payStatus=1/2
 * 表示确认可关单；determined=false 表示降级或查询失败，不得据此关单/退款 —— 降级只能表达
 * "我没查到"，绝不能表达"业务上不存在"。两者数据上都是空，业务后果完全相反。
 */
@Data
@Schema(description = "支付单状态查询结果（三态：已支付/未支付/未知）")
public class PayOrderStatusDTO {

    /**
     * 支付单状态：0 未提交、1 待支付、2 已关闭、3 支付成功；未知时为 null。
     *
     * {@code @JsonAlias("status")}：兼容尚未升级、仍返回支付单本体（字段名 status）的支付服务，
     * 否则滚动发布期间交易域会把所有订单都判成"未知"而永不关单。
     */
    @JsonAlias("status")
    @Schema(description = "支付单状态：0 未提交，1 待支付，2 已关闭，3 支付成功；未知时为 null")
    private Integer payStatus;

    /** 是否确认存在支付单；用户从没发起过支付时为 false，这本身是一个确定的业务结论 */
    @Schema(description = "是否确认存在支付单")
    private Boolean found;

    /** 状态是否已确定；false 表示查询降级/失败，此时 payStatus 与 found 都不可信，必须按"未知"处理 */
    @Schema(description = "状态是否已确定（false=降级或查询失败，不得据此做业务判断）")
    private Boolean determined;

    /** 状态未知的原因，仅用于日志与告警 */
    @Schema(description = "状态未知的原因（仅用于日志）")
    private String unknownReason;

    /**
     * 状态是否已确定：显式 determined=true，或返回体里带着支付单状态（兼容旧版字段 status，
     * 能拿到状态就说明这次查询真的成功过）二者满足其一；"未知"绝不允许被调用方读成"未支付"。
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

    /** 查询成功，但确实没有这笔业务的支付单 */
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
