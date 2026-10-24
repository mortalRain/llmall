package com.llmall.gateway.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 网关侧的运维端点收口：外部经 8080 只允许访问 /actuator/health（含 liveness / readiness），其余一律 404。
 *
 * 【核心职责】
 * 1. 判定：按路径前缀把「健康探针」与「运维端点」分开；
 * 2. 放行：{@code /actuator/health} 及其子路径（{@code /liveness}、{@code /readiness}）原样交给后续处理器；
 * 3. 拦截：其余 {@code /actuator/**} 直接写 404 并打一行 WARN，不进入任何后续处理器。
 *
 * 【为什么是 WebFilter 而不是 Gateway 的 GlobalFilter】
 * GlobalFilter 只在命中路由的请求上执行；而 {@code /actuator/**} 不属于任何路由（网关路由表里没有它），
 * 由网关自身的 WebFlux actuator handler 处理，用 GlobalFilter 拦不到。
 * WebFilter 在 HandlerMapping 之前执行，对包括 actuator 在内的全部请求生效。
 *
 * 【为什么不在配置里改 exposure.include】
 * 实际暴露面来自 Nacos 共享配置（{@code nacos-config/shared-log.yaml}），而 Nacos 远程配置的优先级高于
 * 服务本地 {@code application.yaml}；在网关本地 yaml 里写 include 很可能不生效，而「不生效」没有任何报错，
 * 表现为「配置看起来已经改好、实际暴露面没变」的假修复。这里改为在请求层收口：无论远端配置怎么改，
 * 外部只看得见 health。
 *
 * 【为什么返回 404 而不是 403】
 * 403 等于告诉探测方「这里有个端点，只是不让你看」，反而确认了端点存在；404 与「该路径不存在」一致。
 *
 * 【注意】以下三条边界不许改：
 * 1. 不能拦 {@code /actuator/health}：容器 healthcheck、编排探针与 {@code demo.ps1} 的就绪等待都在打它，
 *    拦掉会让整套环境起不来，表现为「服务都 UP、编排却判定失败」；
 * 2. 不要动 {@code shared-log.yaml} 的 include：业务服务仍然需要 metrics / prometheus 采集，
 *    且它们不对外暴露端口，收掉只会白丢指标；
 * 3. 本类只覆盖网关这一个入口：能直连服务端口的人依旧可以读到该服务的 {@code /actuator/*}，
 *    那一层的防护是网络隔离（端口绑定 / 安全组），不是本过滤器，不要把它当成「内网已安全」的依据。
 *
 * 【可观测性】拦截时打 WARN 而非 DEBUG：这是「有人在探测运维端点」的可检测信号，
 * 与 {@code UserInfoInterceptor} 对「疑似绕过网关直连」打 WARN 的口径一致，让可疑访问在日志里留下痕迹，
 * 而不是静默 404。日志只记 remoteAddr 与 path，不记任何请求头，避免把令牌写进日志。
 *
 * @author 同城生活
 * @since 2026-10-17
 */
@Slf4j
@Component
public class ActuatorExposureFilter implements WebFilter, Ordered {

    /** 运维端点根路径；精确匹配它本身也按「非健康探针」处理（该路径没有业务含义，返回 404 即可） */
    private static final String ACTUATOR_ROOT = "/actuator";

    /** 唯一放行的前缀：/actuator/health 及其子路径 */
    private static final String HEALTH_PREFIX = "/actuator/health";

    /** 指标端点路径：只在演练实例上按开关放行，默认仍返回 404 */
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    /**
     * 是否额外放行 /actuator/prometheus，默认 false。
     *
     * 【用途】仅用于本地演练/截图：需要 Prometheus 抓网关指标，而本过滤器默认把非 health 端
     * 一律 404。生产保持默认，指标由内网直连或独立管理端口采集。
     */
    @Value("${llmall.actuator.prometheus-public:false}")
    private boolean prometheusPublic;

    /**
     * 过滤器主流程：非 actuator 请求一律快速放行，不给正常业务流量增加判定成本。
     *
     * 【边界约束】放行分支不修改请求；拦截分支不调用 {@code chain}，也不写任何响应头，
     * 保持与「框架自己返回 404」尽可能一致，不向探测方泄露「这里有一道过滤器」。
     *
     * @param exchange 当前请求交换对象
     * @param chain    后续过滤器链；仅在放行时必须调用
     * @return 放行时为下游处理完成的信号；拦截时为响应写完的信号
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (!isActuatorPath(path)) {
            return chain.filter(exchange);
        }
        if (isHealthPath(path)) {
            return chain.filter(exchange);
        }
        if (prometheusPublic && PROMETHEUS_PATH.equals(path)) {
            return chain.filter(exchange);
        }
        return reject(exchange, path);
    }

    /**
     * 是否落在运维端点命名空间内。
     *
     * 【注意】必须按路径段判断，不能写成 {@code startsWith("/actuator")}：
     * 后者会把 {@code /actuatorXxx} 这类业务路径也当成运维端点拦掉，未来新增同名开头的路由就会中招。
     *
     * @param path 应用内路径（不含上下文路径与查询串）
     * @return true 表示这是 {@code /actuator} 或 {@code /actuator/**}
     */
    private boolean isActuatorPath(String path) {
        return ACTUATOR_ROOT.equals(path) || path.startsWith(ACTUATOR_ROOT + "/");
    }

    /**
     * 是否为允许放行的健康探针路径。
     *
     * 【设计意图】按前缀放行 {@code /actuator/health} 这一棵子树，从而覆盖
     * {@code /actuator/health}、{@code /actuator/health/liveness}、{@code /actuator/health/readiness}
     * 以及将来新增的 health 子探针；同时不会顺带放行 {@code /actuator/healthz} 这类同名开头的路径。
     *
     * @param path 应用内路径
     * @return true 表示这是健康探针，应当放行
     */
    private boolean isHealthPath(String path) {
        return HEALTH_PREFIX.equals(path) || path.startsWith(HEALTH_PREFIX + "/");
    }

    /**
     * 拦截：写 404（空 body）并记一条 WARN。
     *
     * 【注意】不设 Content-Type、不写 body：空 404 与「路由不存在」的响应最接近；
     * 若在这里返回一段自定义 JSON，等于给探测方一个稳定指纹，可被主动扫描器识别出来。
     *
     * @param exchange 当前请求交换对象
     * @param path     被拦截的路径，仅用于日志
     * @return 响应写完即完成的信号
     */
    private Mono<Void> reject(ServerWebExchange exchange, String path) {
        ServerHttpResponse response = exchange.getResponse();
        // 用 setRawStatusCode 而非 setStatusCode：不触发 Spring 的默认错误处理，保证返回的就是一个裸 404
        response.setRawStatusCode(HttpStatus.NOT_FOUND.value());
        log.warn("非健康类的 actuator 端点已被网关拦截（疑似运维端点探测）: remoteAddr={}, path={}",
                remoteAddrOf(exchange.getRequest()), path);
        return response.setComplete();
    }

    /**
     * 取远端地址用于日志。
     *
     * 【边界约束】{@code getRemoteAddress()} 在部分转发或测试场景下为 null，这里统一转成字符串，
     * 避免日志里出现 null 让排障时误以为取址失败。
     *
     * @param request 当前请求
     * @return 远端地址字符串；取不到时返回 unknown
     */
    private String remoteAddrOf(ServerHttpRequest request) {
        return request.getRemoteAddress() == null ? "unknown" : request.getRemoteAddress().toString();
    }

    /**
     * 过滤器顺序：最高优先级，最先执行。
     *
     * 【取值依据】被拦截的请求不该再走后续任何过滤器（也没有理由为探测流量付出成本），
     * 因此取 {@link Ordered#HIGHEST_PRECEDENCE}；放行分支只做两次字符串比较，对正常流量无明显开销。
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
