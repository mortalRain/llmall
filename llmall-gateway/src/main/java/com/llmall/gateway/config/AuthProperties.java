package com.llmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关鉴权白名单配置，配置前缀 llmall.auth。
 *
 * 【核心职责】把"哪些路径不强制登录"以及"其中哪些本身就是写接口"交给配置，由鉴权过滤器按路径与 HTTP 方法取值。
 *
 * 【注意】本类只存在于网关模块，是白名单的唯一生效位置：其它模块 yaml 里的 llmall.auth.excludePaths
 * 没有任何读取方，写了不报错也不生效（假安全），新增白名单只能改网关模块的配置。
 *
 * 【边界约束】三个清单职责不同、不能互相替代：excludePaths 只对安全方法（GET/HEAD/OPTIONS）生效；
 * unsafeExcludePaths 只登记"未登录也必须能调"的写接口；includePaths 当前没有调用方。
 */
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
