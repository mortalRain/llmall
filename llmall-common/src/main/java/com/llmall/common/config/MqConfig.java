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
     * MQ 消息序列化复用容器内的 ObjectMapper（已注册 JavaTimeModule 与项目定制），与 HTTP 接口同一套规则。
     *
     * 【注意】不能用无参构造的 {@code Jackson2JsonMessageConverter}：它内部自建一个干净的 ObjectMapper，
     * 不认 LocalDateTime，也不带 JsonConfig 的 Long→String，结果是接口返回正常、MQ 消费端报解析错误。
     */
    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 发送 MQ 消息时把当前线程的 traceId 写进消息头，使消费端日志能与发起它的那次请求关联。
     *
     * 【边界】只保证生产者把 traceId 放进消息头；消费端要放进 MDC 需另配 MessageListenerContainer
     * 的 advice 链（当前未做，消费端可先从消息头手工读取）。
     *
     * 【注意】必须是 static 方法：BeanPostProcessor 要先于其它 bean 实例化，非 static 会触发"过早初始化"告警。
     * 不用 Boot 的 RabbitTemplateCustomizer，是因为本项目 Spring Boot 2.7.12 里没有这个类；
     * 这里只依赖 Spring AMQP 的 {@code RabbitTemplate#setBeforePublishPostProcessors}，不绑定 Boot 版本的命名。
     */
    @Bean
    public static BeanPostProcessor rabbitTemplateTraceIdPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RabbitTemplate) {
                    ((RabbitTemplate) bean).setBeforePublishPostProcessors(message -> {
                        // 只在没有 traceId 时补一个，绝不覆盖已有的：死信重投（DeadLetterPublisher）
                        // 会把"最初那条消息的 traceId"原样带过来，覆盖掉它等于切断死信与最初那次请求的联系。
                        if (message.getMessageProperties().getHeaders().get(TraceContext.HEADER) == null) {
                            // 用 generate() 而不是 currentOrGenerate()：MQ 消费线程是复用的，后者会把新生成的
                            // id 写进 MDC 且没人清理，导致同线程后续无关的消息都顶着上一个 traceId 打日志。
                            String traceId = TraceContext.current();
                            message.getMessageProperties().setHeader(TraceContext.HEADER,
                                    traceId == null ? TraceContext.generate() : traceId);
                        }
                        return message;
                    });
                }
                return bean;
            }
        };
    }
}
