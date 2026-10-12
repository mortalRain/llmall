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
 * <h3>为什么"请求头里有 user-info"本身不能作为身份依据</h3>
 * user-info 只是一段普通 HTTP 头，**任何能直连服务端口的人都可以自己写一个**。
 * 服务默认监听 0.0.0.0，网关并不是唯一的入口 —— 所以"网关会剥离伪造头"这条保障
 * 只对"经过网关的流量"成立，对直连流量完全不成立：
 * 直接请求 http://内网IP:8084/users/me 并带上 {@code user-info: 1}，旧实现会原样采信，
 * 等于把身份伪造的入口留在了内网端口上（典型漏洞：只加固了正门，侧门没锁）。
 *
 * <h3>加固后的信任规则（fail-closed）</h3>
 * 只有当请求同时携带 {@code x-internal-token} 且与配置项 {@code llmall.internal.token} 完全一致时，
 * 才采信 user-info。这个凭证由**网关**（认证通过后）与**服务间 Feign 调用**注入，
 * 而网关在入口处会剥离外部请求自带的该头（见 AuthGlobalFilter），因此外部永远伪造不出来。
 * 不满足条件时按"未登录"处理，并打 WARN 把"疑似绕过网关直连"这件事变成可检测信号 ——
 * 静默忽略虽然也不越权，但会让攻击行为完全无痕，运维侧失去发现的机会。
 *
 * <h3>其它加固点（相对课程原版）</h3>
 * 1. 原实现直接 Long.valueOf(userInfo)，非法值（"abc"）会抛 NumberFormatException 变成 500，
 *    既是误报也把内部异常暴露给调用方；现在非法值按"未登录"处理并告警。
 * 2. 请求头可能带多个值或被空格污染，统一 trim 后再解析。
 * 3. 常量统一取自 AuthConstants，避免各处硬编码字符串写错。
 */
@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    /** 与配置项 llmall.internal.token 比对用的内部调用凭证；为空表示未配置（此时一律不采信 user-info） */
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
        // 【关键判断】头存在 ≠ 身份可信：必须同时持有只在服务间流通的内部凭证。
        // 这里用 fail-closed（不满足就不采信）而不是 fail-open：
        // "忘了配置"的后果应当是"身份取不到、请求被拒"，而不是"任何人都能自称任意用户"。
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
            // 非法用户头：按未登录处理，仅告警，不抛异常（正常流量不会走到这里，出现即说明有人在试探）
            log.warn("非法的 {} 请求头: {}, uri={}", AuthConstants.HEADER_USER_INFO, mask(userInfo), request.getRequestURI());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.removeUser();
    }

    /**
     * 日志脱敏：user-info 只保留前 8 个字符。
     * 它虽然是"用户 id"这类低敏感值，但日志里原样打印攻击者可控的任意长字符串
     * 本身就有日志注入风险（换行符可伪造日志行），截断后既够定位又能规避。
     */
    private String mask(String userInfo) {
        String trimmed = userInfo.trim();
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8) + "...";
    }
}
