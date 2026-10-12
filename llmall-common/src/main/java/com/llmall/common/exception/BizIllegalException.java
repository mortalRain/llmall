package com.llmall.common.exception;

/**
 * 业务拒绝异常（库存不足、余额不足、券不可用、状态不允许……）。
 *
 * <p>为什么 code 是 400 而不是 500：这类异常的含义是"你的请求按业务规则不能执行"，
 * 与"服务器自己坏了"（500）是两件事。用 500 表达业务拒绝会带来三个实际后果：
 * ① 前端把正常业务提示当成系统故障（例如弹"服务器内部异常"而不是"库存不足"）；
 * ② 监控里 5xx 率被业务拒绝刷高，真正的故障告警被淹没；
 * ③ 调用方（其它服务）无法靠状态码决定"要不要重试" —— 400 不该重试，500 才应该。
 * 前端只读响应体里的 msg、非 2xx 也会展示 msg，因此改成 400 不会影响提示文案。
 */
public class BizIllegalException extends CommonException{

    public BizIllegalException(String message) {
        super(message, 400);
    }

    public BizIllegalException(String message, Throwable cause) {
        super(message, cause, 400);
    }

    public BizIllegalException(Throwable cause) {
        super(cause, 400);
    }
}
