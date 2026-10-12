package com.llmall.common.exception;

/**
 * 令牌过期（与"令牌非法"区分开）。
 *
 * 为什么要单独一个异常：过期的 access token 是**正常现象**，网关应当尝试用 refresh token
 * 换发新令牌（无感刷新）；而签名错误、格式错误的 token 必须直接拒绝。
 * 如果把两者混为一谈，就没法实现无感刷新——这正是很多"JWT 教程实现"缺失的一环。
 */
public class TokenExpiredException extends UnauthorizedException {

    public TokenExpiredException(String message) {
        super(message);
    }

    public TokenExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
