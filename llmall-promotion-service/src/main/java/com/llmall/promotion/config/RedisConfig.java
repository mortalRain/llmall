package com.llmall.promotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 相关配置：把秒杀 Lua 脚本注册成一个单例 Bean。
 *
 * 做成 Bean 而不是每次调用 new：脚本内容启动时读入并缓存，请求路径上不再有文件 IO；
 * 脚本文件缺失/路径写错会在启动时直接失败（fail fast），而不是等第一个用户点"抢购"才报错；
 * DefaultRedisScript 内部用 SHA1 摘要 + EVALSHA，Redis 端也缓存脚本，执行时不会重复传输脚本体。
 *
 * 【注意】不自定义 RedisTemplate 序列化器：秒杀读写全是数字与短字符串，
 * 直接用自动装配的 StringRedisTemplate；混用 JDK 序列化会写出带类型头、无法用 redis-cli 排查的乱码值。
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
