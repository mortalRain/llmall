package com.llmall.search.es;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索引擎开关（llmall.search.*）。
 *
 * 做成开关而不是直接换成 ES：一是可回退，ES 挂了改配置就回到 MySQL 读模型检索，不必发版；
 * 二是可对照，同一份代码让 engine=es 与 engine=mysql 各压一轮，才能产出对比数据。
 *
 * engine 取值：
 *   - {@code es}   ：只用 ES，不可用时直接失败（避免把回落耗时混进压测数据）
 *   - {@code mysql}：只用 MySQL 读模型（等价于引入 ES 之前的行为）
 *   - {@code auto} ：默认。优先 ES，抛错则回落 MySQL 并打 warn 日志
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
        /** 启动时索引不存在则自动创建；生产建议由运维建索引后置 false */
        private boolean autoCreateIndex = true;

        /** 打分参数：热度分的权重（field_value_factor 的 factor） */
        private double hotScoreFactor = 1.0;

        /** 打分参数：时间衰减的尺度，如 30d（越小越偏向"新店"） */
        private String timeDecayScale = "30d";

        /** 打分参数：评分与评价数的加成权重 */
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
