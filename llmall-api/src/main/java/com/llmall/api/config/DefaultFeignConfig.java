package com.llmall.api.config;

import com.llmall.api.fallback.ItemClientFallback;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.trace.TraceContext;
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
     * 【注意】下游 UserInfoInterceptor 只在请求同时携带内部凭证时才采信 user-info ——
     * 服务监听 0.0.0.0，能直连端口的人可以自己写一个 user-info 冒充任意用户。
     * 网关会给"经网关的请求"注入本头，而服务到服务的调用根本不经过网关，必须由这里补齐，
     * 否则内部调用透传的 user-info 会被下游当成伪造头丢掉，表现为门店/营销域莫名其妙 401。
     * 值由各服务/网关的 llmall.internal.token 统一配置，外部拿不到（网关会剥离客户端自带的同名头）。
     */
    @Value("${llmall.internal.token:}")
    private String internalToken;

    /**
     * 服务间调用凭证（llmall.internal.service-token）。
     *
     * 【注意】与 {@link #internalToken} 的区别：x-internal-token 网关会给每个已登录用户注入，
     * 只能证明"请求经过了网关"；x-service-token 网关只剥离、不注入，只有真正的服务间调用才携带，
     * 资金类内部接口（扣款/退款/标记已支付）用它做入口守卫。必须由这里统一补齐，
     * 否则下游的 fail-closed 校验会把合法的服务调用一起拒掉。
     */
    @Value("${llmall.internal.service-token:}")
    private String serviceToken;

    /**
     * Feign 日志级别。
     *
     * 【注意】取 BASIC 而非 FULL：FULL 会把请求头与请求体原文打进日志，而几个内部接口的请求体里
     * 带的是支付密码/退款信息，等于明文凭据落盘；BASIC 保留"方法 + URL + 状态码 + 耗时"，
     * 足以定位是哪一次调用、慢在哪、成功没有。
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
                // 全链路 traceId：当前线程取不到就现生成一个（定时任务/后台线程发起的调用），
                // 避免下游服务的日志变成无主日志
                template.header(TraceContext.HEADER, TraceContext.currentOrGenerate());
                // 内部凭证：只有我们自己的调用才配带上它；空值不写，下游是严格相等比对，空串必然不通过
                if (internalToken != null && !internalToken.isEmpty()) {
                    template.header(AuthConstants.HEADER_INTERNAL_TOKEN, internalToken);
                }
                // 服务间凭证：资金类内部接口（扣款/退款/标记已支付）靠它区分"服务调用"与"用户请求"
                if (serviceToken != null && !serviceToken.isEmpty()) {
                    template.header(AuthConstants.HEADER_SERVICE_TOKEN, serviceToken);
                }
                Long userId = UserContext.getUser();
                if(userId == null) {
                    return;
                }
                template.header("user-info", userId.toString());
            }
        };
    }

    @Bean
    public ItemClientFallback itemClientFallback(){
        return new ItemClientFallback();
    }
}
