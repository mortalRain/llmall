package com.llmall.common.utils;

/**
 * 当前登录用户的线程内上下文。
 *
 * 【注意】值存在 ThreadLocal 里，只在当前线程可见：异步线程与线程池任务不会自动继承，需要显式传递；
 * 请求处理结束后必须清理，否则线程复用时会把用户带到下一个请求上。
 */
public class UserContext {
    private static final ThreadLocal<Long> tl = new ThreadLocal<>();

    public static void setUser(Long userId) {
        tl.set(userId);
    }

    public static Long getUser() {
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
