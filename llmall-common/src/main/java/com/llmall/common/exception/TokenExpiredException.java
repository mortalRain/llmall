package com.llmall.common.exception;

/**
 * 令牌过期（与"令牌非法"区分开）。
 *
 * 【注意】过期是正常现象，网关据此用 refresh token 换发新令牌（无感刷新）；
 * 签名错误、格式错误的 token 必须直接拒绝，混为一谈会让无感刷新无法实现。
 */
public class TokenExpiredException extends UnauthorizedException {

    public TokenExpiredException(String message) {
        super(message);
    }

    public TokenExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
