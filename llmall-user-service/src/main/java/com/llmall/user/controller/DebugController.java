package com.llmall.user.controller;

import com.llmall.common.utils.UserContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自测接口：用于证明"网关已剥离客户端伪造的 user-info 头"。
 *
 * 为什么需要它：原实现只在通过鉴权后才写入 user-info，而免鉴权路径（excludePaths）直接放行，
 * 攻击者只要自带 `user-info: 1` 就能冒充任意用户。这是一个真实存在、且很难被发现的越权漏洞
 * （因为正常流量完全看不出问题）。本接口故意放在免鉴权路径中，把"下游实际收到的头"和
 * "被采信的身份"打印出来，让漏洞可复现、修复可验证。
 *
 * 默认关闭，仅本地开启：llmall.debug.enabled=true。生产环境请勿开启。
 */
@Slf4j
@Tag(name = "自测接口（默认关闭）")
@RestController
@RequestMapping("/users/_debug")
@ConditionalOnProperty(name = "llmall.debug.enabled", havingValue = "true")
public class DebugController {

    @GetMapping("/whoami")
    public Map<String, Object> whoami(
            @RequestHeader(value = "user-info", required = false) String userInfoHeader,
            HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", request.getRequestURI());
        // 下游实际收到的 user-info 头：修复前伪造值会被透传，修复后恒为 null
        result.put("userInfoHeader", userInfoHeader);
        // 被下游采信的身份
        result.put("userIdInContext", UserContext.getUser());
        result.put("tip", "本接口在网关的免鉴权路径中：若 userInfoHeader 非 null，说明伪造头未被剥离（存在越权风险）");
        log.warn("自测接口被调用, 收到的 user-info 头 = {}", userInfoHeader);
        return result;
    }
}
