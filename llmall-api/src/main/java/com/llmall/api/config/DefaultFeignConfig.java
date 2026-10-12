package com.llmall.api.config;

import com.llmall.api.fallback.ItemClientFallback;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.utils.UserContext;
import feign.Logger;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class DefaultFeignConfig {

    /**
     * 内部调用凭证（llmall.internal.token）。
     *
     * <p>【为什么 Feign 也要带这个头】下游服务的 UserInfoInterceptor 不再无条件相信 user-info：
     * 服务监听 0.0.0.0，能直连端口的人可以自己写一个 user-info 冒充任意用户，
     * 因此它要求请求同时携带只在服务间流通的内部凭证才采信该头。
     * 网关会给"经网关的请求"注入这个头，而服务到服务的调用（本拦截器发出的那些）
     * 根本不经过网关 —— 少了这一步，内部调用透传的 user-info 会被下游当成伪造头丢掉，
     * 表现为"门店/营销域莫名其妙 401、说我未登录"。所以它必须由这里补齐。
     * 值由各服务/网关的 llmall.internal.token 统一配置，外部拿不到（网关会剥离客户端自带的同名头）。
     */
    @Value("${llmall.internal.token:}")
    private String internalToken;

    /**
     * 服务间调用凭证（llmall.internal.service-token）。
     *
     * <p>与 {@link #internalToken} 的区别：x-internal-token 网关会给"每个已登录用户"注入，
     * 所以它只能证明"请求经过了网关"；x-service-token 网关**只剥离、不注入**，
     * 只有真正的服务间调用才携带 —— 资金类内部接口（扣款/退款/标记已支付）用它做入口守卫。
     * 这里统一补齐，否则下游的 fail-closed 校验会把合法的服务调用一起拒掉。
     */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * Feign 日志级别。
     *
     * 【为什么从 FULL 降为 BASIC】FULL 会把请求头与**请求体原文**打进日志，
     * 而本项目几个内部接口的请求体里带的是支付密码/退款信息
     * （实测日志里出现过 `PUT http://llmall-user-service/users/money/deduct?pw=123456&amount=25600`）。
     * 密码进日志 = 明文凭据落盘 + 被日志采集/巡检人员看到，属于典型的凭据泄露面，
     * 而且排查问题时几乎没人真正需要这份 body。
     * BASIC 保留"方法 + URL + 状态码 + 耗时"，出问题足以定位是哪一次调用、慢在哪、成功没有，
     * 但不再打印 body 与 header —— 诊断能力与泄露风险之间的合理取舍。
     * （另：DEBUG 级别的 com.llmall 日志也会连带打印大量 SQL 与参数，已在 shared-log.yaml 里调回 info。）
     */
    @Bean
    public Logger.Level feignLogLevel(){
        return Logger.Level.BASIC;
    }

    @Bean
    public RequestInterceptor userInfoRequestInterceptor(){
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                // 内部凭证：与 user-info 同生共死 —— 只有"我们自己的调用"才配带上它。
                // 空值不写，避免给下游一个"看似有值实则空串"的凭证（下游是严格相等比对，空串必然不通过）。
                if (internalToken != null && !internalToken.isEmpty()) {
                    template.header(AuthConstants.HEADER_INTERNAL_TOKEN, internalToken);
                }
                // 服务间凭证：资金类内部接口（扣款/退款/标记已支付）靠它区分"服务调用"与"用户请求"
                if (serviceToken != null && !serviceToken.isEmpty()) {
                    template.header(AuthConstants.HEADER_SERVICE_TOKEN, serviceToken);
                }
                // 获取登录用户
                Long userId = UserContext.getUser();
                if(userId == null) {
                    // 如果为空则直接跳过
                    return;
                }
                // 如果不为空则放入请求头中，传递给下游微服务
                template.header("user-info", userId.toString());
            }
        };
    }

    //在DefaultFeignConfig中注册成bean
    @Bean
    public ItemClientFallback itemClientFallback(){
        return new ItemClientFallback();
    }
}
