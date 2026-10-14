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
 * 越权自测接口：打印下游实际收到的 user-info 头与被采信的身份，用于验证网关是否已剥离客户端伪造头。
 *
 * 【注意】默认关闭，需 llmall.debug.enabled=true 才装配；仅供本地越权自测，生产环境禁止开启。
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
        result.put("userInfoHeader", userInfoHeader);
        result.put("userIdInContext", UserContext.getUser());
        result.put("tip", "本接口在网关的免鉴权路径中：若 userInfoHeader 非 null，说明伪造头未被剥离（存在越权风险）");
        log.warn("自测接口被调用, 收到的 user-info 头 = {}", userInfoHeader);
        return result;
    }
}
