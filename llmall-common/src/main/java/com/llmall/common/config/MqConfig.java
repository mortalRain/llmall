package com.llmall.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.trace.TraceContext;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.config.BeanPostProcessor;
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

    /**
     * 让 MQ 消息也带上 traceId：发送时把当前线程的 traceId 写进消息头。
     *
     * <p>为什么值得做：下单是"HTTP 请求 + 异步消息"混合链路 —— 订单创建（HTTP）之后，
     * 关单/发积分/清购物车/更新热度都是 MQ 消费。如果没有这个头，消费端日志就与发起它的那次请求
     * 彻底失联，只能靠 orderId 反查，跨服务时还要拼好几张表。
     *
     * <p>【边界】这里保证的是**生产者**把 traceId 放进消息头；消费端要把它放进 MDC
     * 需要用 MessageListenerContainer 的 advice 链另行注入（当前未做，消费端可先从消息头手工读取）。
     * 不把没做的事说成做了，是这份代码注释的基本要求。
     *
     * <p>【为什么用 BeanPostProcessor 而不是 RabbitTemplateCustomizer】
     * 本项目的 Spring Boot 2.7.12 里 `org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer`
     * 并不存在（编译期实测报"找不到符号"）。BeanPostProcessor 只依赖 Spring AMQP 自己的
     * `RabbitTemplate#setBeforePublishPostProcessors`，不绑定 Boot 版本的定制点命名。
     * 必须是 static 方法：BeanPostProcessor 要在其它 bean 之前实例化，非 static 会触发"过早初始化"告警。
     */
    @Bean
    public static BeanPostProcessor rabbitTemplateTraceIdPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RabbitTemplate) {
                    ((RabbitTemplate) bean).setBeforePublishPostProcessors(message -> {
                        message.getMessageProperties().setHeader(TraceContext.HEADER, TraceContext.currentOrGenerate());
                        return message;
                    });
                }
                return bean;
            }
        };
    }
}
