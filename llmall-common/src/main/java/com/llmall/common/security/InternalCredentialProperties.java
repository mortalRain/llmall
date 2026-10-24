package com.llmall.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 服务间凭证的配置绑定与启动期校验。
 *
 * 【为什么需要它】这两个凭证此前只有 YAML 里的演示默认值，代码侧没有任何机制阻止
 * "生产环境仍在使用公开的示例口令"。示例值一旦生效，能直连服务端口的人就可以伪造身份、
 * 调用扣款/退款/标记已支付这类内部接口。
 *
 * 【校验口径】reject-demo 由部署编排显式打开（容器环境设为 true）：
 * 此时凭证必须存在、长度不少于 32，且不能等于仓库里的示例值，否则应用拒绝启动。
 * 本地开发不打开该开关，保留 YAML 默认值以便 IDEA 直接运行。
 */
@Data
@ConfigurationProperties(prefix = "llmall.internal")
public class InternalCredentialProperties {

    /** 网关写入、下游采信 user-info 时校验的内部凭证 */
    private String token;

    /** 服务间调用专用凭证，网关只剥离不注入 */
    private String serviceToken;

    /** 为 true 时执行"禁止示例凭证"的启动校验 */
    private boolean rejectDemo;

    private static final Set<String> DEMO_VALUES = new HashSet<>(Arrays.asList(
            "llmall-internal-demo-token", "llmall-service-demo-token"));

    private static final int MIN_LENGTH = 32;

    @PostConstruct
    void validate() {
        if (!rejectDemo) {
            return;
        }
        requireStrong("llmall.internal.token", token);
        // 网关不配置 service-token（它只剥离、不注入该头），因此只在"存在"时校验；
        // 业务服务若漏配，内部接口本身是 fail-closed 的（403），不会退化成公开接口。
        if (serviceToken != null && !serviceToken.trim().isEmpty()) {
            requireStrong("llmall.internal.service-token", serviceToken);
        }
    }

    private void requireStrong(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(key + " 未配置：生产环境必须由部署编排注入");
        }
        if (value.length() < MIN_LENGTH) {
            throw new IllegalStateException(key + " 长度不足 " + MIN_LENGTH + "，疑似仍在使用示例值");
        }
        if (DEMO_VALUES.contains(value)) {
            throw new IllegalStateException(key + " 仍是仓库内的示例值，禁止在非本地环境使用");
        }
    }
}
