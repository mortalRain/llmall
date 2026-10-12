package com.llmall.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@ConditionalOnClass(RabbitTemplate.class)
public class MqConfig {

    /**
     * 修复：原实现是 `new Jackson2JsonMessageConverter()`，它内部**自建**一个干净的 ObjectMapper，
     * 因此不认 Java 8 时间类型（LocalDateTime 直接抛 "Java 8 date/time type not supported by default"），
     * 也不带项目里 JsonConfig 配置的 Long→String 序列化。
     *
     * 这里注入 Spring 容器里的 ObjectMapper（已注册 JavaTimeModule + 项目定制），
     * 让 MQ 消息的序列化规则与 HTTP 接口保持一致 —— 否则会出现"接口返回正常、MQ 消费端报解析错"的怪问题。
     */
    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
