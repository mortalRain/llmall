package com.llmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "llmall.auth")
public class AuthProperties {
    private List<String> includePaths;
    private List<String> excludePaths;

    /**
     * 免鉴权路径中本身就是写操作的接口（登录、发码等）。
     *
     * 【注意】不能统一按"白名单路径全放行"处理：公开只读接口的同前缀下还有改价、上下架、
     * 回滚库存等写接口，会一起被放开；这类写接口只能显式登记在本例外清单里。
     */
    private List<String> unsafeExcludePaths;
}
