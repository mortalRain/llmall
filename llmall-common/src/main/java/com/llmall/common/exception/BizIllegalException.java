package com.llmall.common.exception;

/**
 * 业务拒绝异常（库存不足、余额不足、券不可用、状态不允许……），HTTP 状态码固定为 400。
 *
 * 【注意】400 表示"请求按业务规则不可执行"，与 500（服务器故障）必须区分：用 500 表达业务拒绝
 * 会让前端把业务提示当系统故障、让 5xx 率被业务拒绝刷高、让调用方误判为可重试；
 * 前端只读响应体里的 msg、非 2xx 也会展示 msg，故用 400 不影响提示文案。
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
