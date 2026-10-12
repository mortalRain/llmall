package com.llmall.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 相关配置：把秒杀 Lua 脚本注册成一个单例 Bean。
 *
 * 为什么要把脚本做成 Bean 而不是每次调用 new：
 * 1. 脚本内容在启动时读入并缓存，请求路径上不再有文件 IO；
 * 2. 脚本文件缺失/语法文件路径写错会在**启动时**直接失败（fail fast），
 *    而不是等到大促当天第一个用户点"抢购"才报错；
 * 3. DefaultRedisScript 内部用 SHA1 摘要 + EVALSHA，Redis 端也缓存脚本，
 *    实际执行不会每次传输脚本体。
 *
 * 说明：这里不需要自定义 RedisTemplate 序列化器 —— 秒杀读写全是数字与短字符串，
 * 直接用 Spring Boot 自动装配的 StringRedisTemplate 最省心，
 * 混用 JDK 序列化反而会写出带类型头、无法用 redis-cli 直接排查的乱码值。
 */
@Configuration
public class RedisConfig {

    @Bean
    public DefaultRedisScript<Long> seckillScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
