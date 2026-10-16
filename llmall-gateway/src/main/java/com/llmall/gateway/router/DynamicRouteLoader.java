package com.llmall.gateway.router;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.llmall.common.utils.CollUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 网关动态路由加载器：监听 Nacos 的 gateway-routes.json，配置变更时同步本机路由表。
 *
 * 【核心职责】启动时拉取一次配置建立路由表，之后由 Nacos 监听器在配置变更时全量重建本机路由表。
 *
 * 【设计意图】路由不走 Spring Cloud Gateway 的 routes 属性源，而是把配置直接反序列化成 RouteDefinition
 * 写入 RouteDefinitionWriter，原因有两条：
 * 1. 这份配置的根节点是 JSON 数组，无法展开成属性源的键值对，按属性源导入同一 dataId 会直接报错；
 * 2. 配置里的中文注释在中文 Windows 的默认字符集下会被 Nacos 按 UTF-8 解析失败（启动期即报错）。
 * 因此路由只能在 Nacos 上改这份 JSON，不要再把它配成配置属性源（包括 extension-configs）。
 *
 * 【边界约束】每次变更是"先删本机旧路由、再全量写入新路由"，不支持按路由 id 差量更新；
 * 写入是异步的（save / delete 返回的 Mono 在此直接 subscribe），既不等待结果也不处理其错误。
 *
 * 【注意】配置为空（或解析出空列表）时本机路由会被清空且不再写入，等价于"网关不放行任何路由"（fail-closed）：
 * 上游误发一份空配置的表现是全部请求无路由，而不是继续沿用旧路由表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicRouteLoader {

    private final RouteDefinitionWriter writer;
    private final NacosConfigManager nacosConfigManager;

    // 路由表在 Nacos 上的坐标：dataId 与 group 必须与 Nacos 上发布的配置完全一致，写错表现为路由表始终为空
    private final String dataId = "gateway-routes.json";
    private final String group = "DEFAULT_GROUP";
    /** 本机当前已注册的路由 id，配置变更时据此删除旧路由 */
    private final Set<String> routeIds = new HashSet<>();

    /**
     * 启动时拉取一次路由配置，并注册配置变更监听器（由 @PostConstruct 调用）。
     *
     * 【异常】Nacos 不可用时抛 NacosException 直接向上冒泡，使应用启动失败：启动期拿不到路由表的网关
     * 没有任何可用路由，带着空路由表启动比启动失败更难排查。
     * 第三个参数 5000 是本次拉取配置的超时（毫秒）。
     */
    @PostConstruct
    public void initRouteConfigListener() throws NacosException {
        String configInfo = nacosConfigManager.getConfigService()
                .getConfigAndSignListener(dataId, group, 5000, new Listener() {
                    @Override
                    public Executor getExecutor() {
                        // 返回 null：不为回调指定线程池，由 Nacos 客户端自行调度（本类不自建线程池）
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        updateConfigInfo(configInfo);
                    }
                });
        updateConfigInfo(configInfo);
    }

    /**
     * 用最新配置全量重建本机路由表：先按 routeIds 删除旧路由，再逐个写入并记录新的路由 id。
     *
     * 【注意】routeIds 记录的是本机已注册的路由 id，删除只针对本机；多实例部署时各实例各自监听、各自重建，
     * 变更后无需重启即可生效。
     */
    private void updateConfigInfo(String configInfo) {
        log.debug("监听到路由配置变更，{}", configInfo);
        List<RouteDefinition> routeDefinitions = JSONUtil.toList(configInfo, RouteDefinition.class);
        for (String routeId : routeIds) {
            writer.delete(Mono.just(routeId)).subscribe();
        }
        routeIds.clear();
        if (CollUtils.isEmpty(routeDefinitions)) {
            return;
        }
        routeDefinitions.forEach(routeDefinition -> {
            writer.save(Mono.just(routeDefinition)).subscribe();
            routeIds.add(routeDefinition.getId());
        });
    }
}
