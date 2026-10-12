package com.llmall.common.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 最小规则集（限流 + 熔断）。
 *
 * <h3>为什么要有这个类</h3>
 * 在这个类出现之前，项目处于一种"看起来有容错、其实只有一半"的状态：
 * <ul>
 *   <li>Feign 的 {@code fallbackFactory} 声明了、兜底类也写了（而且写得很讲究：资金类失败抛异常、
 *       支付状态返回"未知"而非 null），但**只有调用方 classpath 上有 Sentinel 时才会被执行** ——
 *       于是同一个 {@code ItemClient}，在 cart 里失败走兜底、在 trade 里失败直接抛异常；</li>
 *   <li>全项目 {@code @SentinelResource} 数量为 0、一条规则也没有 —— 就算 classpath 对了，
 *       也只有"调用抛异常时兜底"这一件事在工作，不存在"下游持续失败就自动快速失败"的熔断。</li>
 * </ul>
 * 本类把第二半补上：给出可运行、可验证的最小规则集，并让"哪条链路降级、哪条不降级"成为显式决定。
 *
 * <h3>规则口径</h3>
 * <ul>
 *   <li><b>itemPage（商品列表，全站最热的读接口）</b>：QPS 快速失败限流 + 异常比例熔断。
 *       读接口被限流只是"稍后再试"，不会造成资损，是限流最合适的对象。</li>
 *   <li><b>资金与库存链路不做自动降级</b>：{@code ItemClient#deductStock}、{@code VoucherClient} 的兜底
 *       一律抛异常（见 llmall-api 的 fallback 实现与 VoucherClient 的类注释）。
 *       理由：把"扣库存失败"降级成"假装成功"就是超卖，把"支付状态未知"降级成"未支付"就是资损。
 *       这类链路要的是<b>快速失败 + 本地消息表重试 + 对账补偿</b>，不是降级。</li>
 * </ul>
 *
 * <h3>规则放代码里还是放 Nacos</h3>
 * 这里用代码静态注册，原因是规则数量少且与具体资源强绑定，静态注册"改了要发版"反而让口径更清楚；
 * 需要热更新时再接入 {@code sentinel-datasource-nacos}（届时 {@code loadRules} 会被数据源覆盖，
 * 这个类退化为"兜底默认值"，注意不要两处同时改）。
 *
 * <h3>阈值可配</h3>
 * {@code llmall.sentinel.item-page-qps}（默认 200）—— 演示/压测时可以调低，便于观察限流效果；
 * 生产按容量评估后从配置中心下发。
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "com.alibaba.csp.sentinel.SphU")
public class SentinelRuleConfig {

    /** 被保护的热点资源名（与 ItemController#queryItemByPage 上的 @SentinelResource 一致） */
    private static final String RES_ITEM_PAGE = "itemPage";

    /** 商品列表 QPS 阈值。默认值按"单实例能扛住"估，演示时可调成 2 观察限流 */
    @Value("${llmall.sentinel.item-page-qps:200}")
    private double itemPageQps;

    @PostConstruct
    public void loadMinimalRules() {
        // ---------- ① 流控：超阈值直接快速失败（不排队），由 blockHandler 转成可读的业务错误 ----------
        List<FlowRule> flowRules = new ArrayList<>();
        FlowRule itemPageFlow = new FlowRule(RES_ITEM_PAGE);
        itemPageFlow.setGrade(RuleConstant.FLOW_GRADE_QPS);
        itemPageFlow.setCount(itemPageQps);
        itemPageFlow.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT); // 快速失败（拒绝）而非排队
        flowRules.add(itemPageFlow);
        FlowRuleManager.loadRules(flowRules);

        // ---------- ② 熔断：异常比例超过 50% 且请求数足够时，熔断 10 秒 ----------
        //  熔断期内请求**不再打到下游**（直接走 blockHandler），给下游恢复的时间；
        //  10 秒后放一个探测请求，成功则自动闭合（半开）。
        List<DegradeRule> degradeRules = new ArrayList<>();
        DegradeRule itemPageDegrade = new DegradeRule(RES_ITEM_PAGE);
        itemPageDegrade.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        itemPageDegrade.setCount(0.5);
        itemPageDegrade.setTimeWindow(10);
        itemPageDegrade.setMinRequestAmount(10);
        degradeRules.add(itemPageDegrade);
        DegradeRuleManager.loadRules(degradeRules);

        log.info("[Sentinel] 最小规则集已加载: flow={} 条（{} QPS={}）, degrade={} 条（{} 异常比例>{}/{}s, 最小请求数 10）",
                flowRules.size(), RES_ITEM_PAGE, itemPageQps,
                degradeRules.size(), RES_ITEM_PAGE, 0.5, 10);
    }
}
