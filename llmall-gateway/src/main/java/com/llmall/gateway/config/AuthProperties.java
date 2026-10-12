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
     * 免鉴权路径里"本身就是写操作"的那几个（登录/发码等）。
     *
     * 为什么必须把它们单独列出来，而不是统一"白名单路径全都放行"：
     * 绝大多数公开接口是**只读**的（浏览商品、看门店、看评价），它们之所以免登录
     * 只是因为"看一眼不需要身份"；一旦用 {@code /**} 把路径整段放行，同一个前缀下的
     * 写接口（改价、上下架、回滚库存、删除商品）就被一起放出去了。
     * 所以放行规则必须是"路径 + 方法"两个维度的判断：只读路径只放行安全方法，
     * 而登录/发码这类天生就是 POST 的接口，只能显式登记在这个例外清单里。
     */
    private List<String> unsafeExcludePaths;
}
