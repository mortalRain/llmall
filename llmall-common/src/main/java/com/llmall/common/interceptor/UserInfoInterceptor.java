package com.llmall.common.interceptor;

import cn.hutool.core.util.StrUtil;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 把网关注入的 user-info 请求头解析为当前登录用户，放进 ThreadLocal。
 *
 * 【注意】"请求头里有 user-info"本身不能作为身份依据：它只是一段普通 HTTP 头，任何能直连服务端口的人
 * 都可以自己写一个；服务默认监听 0.0.0.0，网关并不是唯一入口，所以"网关会剥离伪造头"只对经过网关的
 * 流量成立，对直连流量完全不成立。因此仅当 {@code x-internal-token} 与配置项 {@code llmall.internal.token}
 * 完全一致时才采信 user-info，否则一律按未登录处理并打 WARN，让"疑似绕过网关直连"成为可检测信号
 * （fail-closed：不满足条件就不采信，"忘了配置"的后果是身份取不到，而不是任何人都能自称任意用户）。
 *
 * 加固点：非法 user-info（"abc"）按未登录处理并告警，不抛 NumberFormatException 变成 500；解析前统一 trim。
 */
@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    /** 内部调用凭证，与配置项 llmall.internal.token 比对；为空表示未配置（此时一律不采信 user-info） */
    private final String internalToken;

    public UserInfoInterceptor(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userInfo = request.getHeader(AuthConstants.HEADER_USER_INFO);
        if (StrUtil.isBlank(userInfo)) {
            return true;
        }
        // 头存在 ≠ 身份可信：必须同时持有只在服务间流通的内部凭证；这里用 fail-closed，
        // "忘了配置"的后果是身份取不到，而不是任何人都能自称任意用户。
        String requestToken = request.getHeader(AuthConstants.HEADER_INTERNAL_TOKEN);
        if (StrUtil.isBlank(internalToken) || !internalToken.equals(requestToken)) {
            log.warn("user-info 请求头未携带合法内部凭证，已按未登录处理（疑似绕过网关直连服务端口）: "
                            + "remoteAddr={}, uri={}, userInfo={}",
                    request.getRemoteAddr(), request.getRequestURI(), mask(userInfo));
            return true;
        }
        try {
            UserContext.setUser(Long.valueOf(userInfo.trim()));
        } catch (NumberFormatException e) {
            // 非法用户头：按未登录处理，抛异常会变成 500 并把内部异常暴露给调用方
            log.warn("非法的 {} 请求头: {}, uri={}", AuthConstants.HEADER_USER_INFO, mask(userInfo), request.getRequestURI());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.removeUser();
    }

    /** 日志脱敏：只保留前 8 个字符，规避攻击者可控长字符串带来的日志注入（换行符可伪造日志行） */
    private String mask(String userInfo) {
        String trimmed = userInfo.trim();
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8) + "...";
    }
}
