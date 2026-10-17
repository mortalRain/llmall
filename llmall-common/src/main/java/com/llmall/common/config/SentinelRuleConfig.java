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
 * Sentinel 最小规则集（限流 + 熔断）：itemPage 走 QPS 快速失败限流 + 异常比例熔断。
 *
 * 【注意】资金与库存链路不做自动降级：{@code ItemClient#deductStock}、{@code VoucherClient} 的兜底
 * 一律抛异常 —— 把"扣库存失败"降级成成功就是超卖，把"支付状态未知"降级成未支付就是资损，
 * 这类链路要的是快速失败 + 本地消息表重试 + 对账补偿。
 *
 * 规则用代码静态注册而非放 Nacos：规则少且与具体资源强绑定，"改了要发版"反而让口径更清楚；
 * 接入 {@code sentinel-datasource-nacos} 后 {@code loadRules} 会被数据源覆盖，本类退化为"兜底默认值"，
 * 注意不要两处同时改。阈值 {@code llmall.sentinel.item-page-qps}（默认 1000，取值依据见字段注释），演示/压测可调低。
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "com.alibaba.csp.sentinel.SphU")
public class SentinelRuleConfig {

    /** 被保护的资源名，须与 ItemController#queryItemByPage 上的 @SentinelResource 一致 */
    private static final String RES_ITEM_PAGE = "itemPage";

    /** 熔断：异常比例阈值，超过即进入熔断 */
    private static final double DEGRADE_EXCEPTION_RATIO = 0.5;

    /** 熔断：统计窗口与熔断时长（秒），窗口结束后放一个探测请求，成功则自动闭合（半开） */
    private static final int DEGRADE_TIME_WINDOW_SECONDS = 10;

    /** 熔断：窗口内最小请求数，低于该值不触发熔断 */
    private static final int DEGRADE_MIN_REQUEST_AMOUNT = 10;

    /**
     * 商品列表 QPS 阈值，演示/压测时可调低以便观察限流。
     *
     * 【取值依据】1000 = 压测得到的容量（≈1500~1550 rps，见 docs/P-02-商品列表容量与建议阈值-2026-10-17.md）× 约 70 %：
     * 容量是在"该接口接近独占整机"的口径下测的，线上是全站共享，所以取 70 % 这一档留余量。
     * 抬连接池对该接口的容量几乎没有影响（+2~5 %），所以本值不随池大小调整。
     * 【调小后果】把容量压得过低，正常流量被误拒（被拒部分是 4xx，用户可见）；【调大后果】把 70 % 的安全余量吃掉，
     * P99 会先恶化（压测中 1300 rps 起 P99 逐档上台阶）。
     */
    @Value("${llmall.sentinel.item-page-qps:1000}")
    private double itemPageQps;

    @PostConstruct
    public void loadMinimalRules() {
        // 流控：超阈值直接拒绝（不排队），由 blockHandler 转成可读的业务错误
        List<FlowRule> flowRules = new ArrayList<>();
        FlowRule itemPageFlow = new FlowRule(RES_ITEM_PAGE);
        itemPageFlow.setGrade(RuleConstant.FLOW_GRADE_QPS);
        itemPageFlow.setCount(itemPageQps);
        itemPageFlow.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        flowRules.add(itemPageFlow);
        FlowRuleManager.loadRules(flowRules);

        // 熔断：异常比例与请求数双双达标才熔断，熔断期内请求不再打到下游，给下游恢复时间
        List<DegradeRule> degradeRules = new ArrayList<>();
        DegradeRule itemPageDegrade = new DegradeRule(RES_ITEM_PAGE);
        itemPageDegrade.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        itemPageDegrade.setCount(DEGRADE_EXCEPTION_RATIO);
        itemPageDegrade.setTimeWindow(DEGRADE_TIME_WINDOW_SECONDS);
        itemPageDegrade.setMinRequestAmount(DEGRADE_MIN_REQUEST_AMOUNT);
        degradeRules.add(itemPageDegrade);
        DegradeRuleManager.loadRules(degradeRules);

        log.info("[Sentinel] 最小规则集已加载: flow={} 条（{} QPS={}）, degrade={} 条（{} 异常比例>{}/{}s, 最小请求数 {}）",
                flowRules.size(), RES_ITEM_PAGE, itemPageQps,
                degradeRules.size(), RES_ITEM_PAGE,
                DEGRADE_EXCEPTION_RATIO, DEGRADE_TIME_WINDOW_SECONDS, DEGRADE_MIN_REQUEST_AMOUNT);
    }
}



