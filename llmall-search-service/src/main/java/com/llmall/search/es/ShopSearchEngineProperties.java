package com.llmall.search.es;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索引擎开关（llmall.search.*）。
 *
 * 为什么要做成开关而不是直接换成 ES：
 *   1. **可回退**：ES 挂了/索引坏了，把 engine 改成 mysql 就回到原来的读模型检索，
 *      不需要发版、不需要改代码 —— 这是"引入新组件"必须自带的退路；
 *   2. **可对照**：同一份代码、同一个场景，engine=es 与 engine=mysql 各压一轮，
 *      才能产出"ES 到底快了多少"的对比数据（而不是"我觉得 ES 更快"）。
 *
 * engine 取值：
 *   - {@code es}   ：只用 ES；ES 不可用时直接失败（用于压测，避免把回落耗时混进 ES 数据）
 *   - {@code mysql}：只用 MySQL 读模型（等价于引入 ES 之前的行为）
 *   - {@code auto} ：默认。优先 ES，抛错则回落 MySQL 并打 warn 日志（生产上更稳）
 */
@Data
@Component
@ConfigurationProperties(prefix = "llmall.search")
public class ShopSearchEngineProperties {

    /** es | mysql | auto */
    private String engine = "auto";

    private Es es = new Es();

    @Data
    public static class Es {
        /** 启动时若索引不存在则自动创建（本地开发方便；生产建议由运维建索引后置 false） */
        private boolean autoCreateIndex = true;

        /** 打分参数：热度分的权重（field_value_factor 的 factor） */
        private double hotScoreFactor = 1.0;

        /** 打分参数：时间衰减的尺度，如 30d（越小越偏向"新店"） */
        private String timeDecayScale = "30d";

        /** 打分参数：评分/评价数的加成权重 */
        private double scoreBoost = 0.5;
        private double commentsBoost = 0.3;
    }

    public boolean useEs() {
        return "es".equalsIgnoreCase(engine) || "auto".equalsIgnoreCase(engine);
    }

    /** ES 失败时是否允许回落 MySQL（engine=es 时不允许，保证压测数据干净） */
    public boolean fallbackToMysql() {
        return !"es".equalsIgnoreCase(engine);
    }
}
