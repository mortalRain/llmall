package com.llmall.promotion.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 秒杀异步处理结果。
 *
 * 为什么不直接返回字符串：客户端要能区分"还在处理"和"失败原因"，
 * 用固定状态码 + 文案字段，前端不必解析中文字符串。
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class SeckillResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 处理中：请求已被接受，落库还没完成 */
    public static final String PENDING = "PENDING";
    /** 成功：user_voucher 已落库，userVoucherId 可用来核销 */
    public static final String SUCCESS = "SUCCESS";
    /** 失败：message 里是失败原因（库存不足 / 已持有 / 系统繁忙等） */
    public static final String FAILED = "FAILED";

    /** 秒杀凭证（抢购时返回给客户端的 UUID） */
    private String token;

    /** 券 id */
    private Long voucherId;

    /** PENDING / SUCCESS / FAILED */
    private String status;

    /** 成功后返回的券实例 id，可直接用于 /vouchers/my/{id}/use */
    private Long userVoucherId;

    /** 提示文案 */
    private String message;

    public static SeckillResultVO pending(String token, Long voucherId, String message) {
        return new SeckillResultVO()
                .setToken(token).setVoucherId(voucherId)
                .setStatus(PENDING).setMessage(message);
    }

    public static SeckillResultVO success(String token, Long voucherId, Long userVoucherId) {
        return new SeckillResultVO()
                .setToken(token).setVoucherId(voucherId)
                .setStatus(SUCCESS).setUserVoucherId(userVoucherId)
                .setMessage("抢购成功");
    }

    public static SeckillResultVO failed(String token, Long voucherId, String message) {
        return new SeckillResultVO()
                .setToken(token).setVoucherId(voucherId)
                .setStatus(FAILED).setMessage(message);
    }
}
