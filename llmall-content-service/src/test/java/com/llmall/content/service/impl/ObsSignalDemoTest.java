package com.llmall.content.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.content.mapper.FollowMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 台账 OBS-01 的可观测信号演示（2026-10-15）：Feed 写扩散被阈值跳过时必须打出 WARN。
 *
 * 【为什么要这个测试】该缺口此前是静默的（INFO 级）：大 V 发笔记后粉丝在 Feed 里看不到，
 * 而日志里没有任何"这条笔记没有推给任何人"的痕迹。信号加完不算完成 —— 必须证明它真的会触发。
 *
 * 【怎么做到不依赖 DB / Redis】超阈值分支只用 countFollowers（走 baseMapper.selectCount），
 * 因此用动态代理桩替换 baseMapper、并把 redisTemplate 传 null 即可驱动真实代码路径。
 */
class ObsSignalDemoTest {

    private ListAppender<ILoggingEvent> attachAppender(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 桩 FollowMapper：selectCount 返回给定值（模拟"粉丝数超过写扩散阈值"） */
    private FollowMapper stubMapper(long countValue) {
        return (FollowMapper) Proxy.newProxyInstance(
                FollowMapper.class.getClassLoader(),
                new Class<?>[]{FollowMapper.class},
                (proxy, method, args) -> {
                    if ("selectCount".equals(method.getName())) {
                        return countValue;
                    }
                    return null;
                });
    }

    private FollowServiceImpl serviceWith(long followerCount) throws Exception {
        // 走的是"超阈值直接返回"与"粉丝为 0 直接返回"两条分支，两条都不碰 feedInboxMapper / blogMapper / redisTemplate，
        // 所以前三者传 null 即可；指标对象必须给真实实例（跳过分支会累加计数）
        FollowServiceImpl service = new FollowServiceImpl(null, null, null,
                new com.llmall.common.metrics.BizMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        Field baseMapper = ServiceImpl.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(service, stubMapper(followerCount));
        return service;
    }

    @Test
    void overThresholdPushMustEmitWarnSignal() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender(FollowServiceImpl.class);

        serviceWith(999_999L).pushToFollowers(1L, 42L);

        System.out.println("===== OBS-01 触发输出 =====");
        List<ILoggingEvent> events = appender.list;
        events.forEach(e -> System.out.println("  [" + e.getLevel() + "] " + e.getFormattedMessage()));
        System.out.println("===========================");

        assertThat(events)
                .as("粉丝数超阈值跳过推送时必须打出 WARN，且写明这些粉丝看不到本条笔记")
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("写扩散被阈值跳过")
                        && e.getFormattedMessage().contains("不会出现在粉丝 Feed 里"));
    }

    @Test
    void zeroFollowersMustStaySilent() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender(FollowServiceImpl.class);

        // 粉丝数为 0 是常态（新作者），不该产生任何告警 —— 否则信号会变成噪音
        serviceWith(0L).pushToFollowers(1L, 42L);

        assertThat(appender.list)
                .as("粉丝数为 0 时不应打出 WARN")
                .noneMatch(e -> e.getLevel() == Level.WARN);
    }
}
