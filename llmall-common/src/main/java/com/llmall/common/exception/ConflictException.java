package com.llmall.common.exception;

/**
 * 资源冲突异常（HTTP 409）：请求本身合法，但与当前资源状态冲突。
 *
 * 【典型场景】重复添加同一门店员工、撤销最后一个店主、并发写入撞唯一键。
 * 与 {@link BizIllegalException}（400）的区别只在语义：前端可据此提示"刷新后重试"，
 * 而不是把冲突当成参数错误。两者都是 4xx 业务拒绝，统一异常处理器按 code 原样返回。
 */
public class ConflictException extends CommonException {

    public ConflictException(String message) {
        super(message, 409);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause, 409);
    }
}
