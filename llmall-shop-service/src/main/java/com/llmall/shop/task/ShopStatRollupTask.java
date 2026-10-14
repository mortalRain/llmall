package com.llmall.shop.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.llmall.shop.constant.ShopRedisConstants;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.mapper.ShopMapper;
import com.llmall.shop.mapper.ShopStatMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 门店访问统计落库：把 Redis 里的 UV/PV 搬到 MySQL 的 shop_stat 表。
 *
 * 【注意】为什么必须落库：UV 走 HyperLogLog、PV 走计数器，两者都按天新建 key 且只保留
 * {@code llmall.shop.cache.uv-ttl-days} 天（默认 2）。不落库的话，"上周某天有多少人访问过这家门店"
 * 这类数据在两天后就永久消失了。本表是历史留档，实时值仍以 Redis 为准。
 *
 * 幂等：靠 shop_stat 的唯一键 {@code uk_shop_date(shop_id, stat_date)}，任务重跑或多实例同时触发
 * 都只会覆盖成同一份值。
 *
 * 【注意】依赖启动类上的 {@code @EnableScheduling}：漏了注解不会有任何报错，只是数据永远不落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopStatRollupTask {

    private final ShopMapper shopMapper;
    private final ShopStatMapper shopStatMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 防重入：定时触发与运维接口手动触发可能撞在一起。
     * 【注意】这只是进程内开关，多实例部署时各实例各自落库（upsert 幂等，不会出错）。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 默认每天 00:10 落前一天的数据。
     *
     * 用 cron 而非 fixedDelay：fixedDelay 从应用启动时刻起算，会把"统计日期"错开成运行时间点；
     * 错开 10 分钟是为了让跨零点的访问请求先把 key 写完。
     */
    @Scheduled(cron = "${llmall.shop.stat.rollup-cron:0 10 0 * * ?}")
    public void scheduledRollup() {
        rollupYesterday();
    }

    /**
     * 落库前一天的数据；定时与运维手动入口共用。
     *
     * @return 实际写入的门店数
     */
    public int rollupYesterday() {
        if (!running.compareAndSet(false, true)) {
            log.warn("门店访问统计落库已在进行中，本次跳过");
            return 0;
        }
        try {
            return rollup(LocalDate.now().minusDays(1));
        } catch (Exception e) {
            // 定时任务绝不能因为单次异常而终止
            log.error("门店访问统计落库任务执行异常", e);
            return 0;
        } finally {
            running.set(false);
        }
    }

    /**
     * 把指定日期的 UV/PV 落到 shop_stat。
     *
     * 【注意】当天没有任何访问的门店直接跳过，不写 0 值行：写了会用 0 覆盖掉可能已存在的历史行，
     * 而"没访问过"与"统计没跑到"在库里应该区分得开。
     *
     * @return 实际写入的门店数
     */
    public int rollup(LocalDate date) {
        if (date == null) {
            return 0;
        }
        // 逐门店取数：门店量级是"百"，每天一轮的 N 次 PFCOUNT/GET 完全可以接受；
        // 改用 SCAN 遍历 uv:shop:{date}:* 会做大范围键扫描，代价更高且结果是弱一致的。
        List<Long> shopIds = shopMapper.selectList(new QueryWrapper<Shop>().select("id"))
                .stream().map(Shop::getId).collect(Collectors.toList());
        int written = 0;
        for (Long shopId : shopIds) {
            long uv = uvOf(date, shopId);
            long pv = pvOf(date, shopId);
            if (uv == 0 && pv == 0) {
                continue;
            }
            shopStatMapper.upsert(shopId, date, pv, uv);
            written++;
        }
        log.info("门店访问统计落库完成: date={}, 门店总数={}, 有访问并落库={}", date, shopIds.size(), written);
        return written;
    }

    /** 读某天某门店的 UV（HyperLogLog 估算值），key 不存在时返回 0。 */
    private long uvOf(LocalDate date, Long shopId) {
        Long size = redisTemplate.opsForHyperLogLog()
                .size(ShopRedisConstants.SHOP_UV_KEY + date + ":" + shopId);
        return size == null ? 0L : size;
    }

    /** 读某天某门店的 PV（计数器），key 不存在或值非法时返回 0。 */
    private long pvOf(LocalDate date, Long shopId) {
        String value = redisTemplate.opsForValue()
                .get(ShopRedisConstants.SHOP_PV_KEY + date + ":" + shopId);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("PV 计数器的值不是合法数字，按 0 处理: key={}, value={}",
                    ShopRedisConstants.SHOP_PV_KEY + date + ":" + shopId, value);
            return 0L;
        }
    }
}
