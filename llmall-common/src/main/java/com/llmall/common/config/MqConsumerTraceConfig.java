package com.llmall.common.config;

import com.llmall.common.trace.TraceContext;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费端的 traceId 透传：把生产端写进消息头的 traceId 放回 MDC，使异步链路的日志能串起来。
 *
 * 【为什么需要它】生产端已经用 BeforePublishPostProcessor 把 traceId 写进消息头，
 * 但消费线程不会自动继承发送方的 MDC；不补这一步，评价到热度、支付到积分这类异步链路
 * 在日志里是断开的，只能靠业务号反查。
 *
 * 【实现位置】用监听容器工厂的 AfterReceivePostProcessor，在消息交给监听器之前设置 MDC，
 * 不侵入任何 RabbitListener 方法签名。
 *
 * 【边界】消费线程是复用的，这里只保证"每条消息都重新设置一次"，
 * 不保证处理结束后清空：下一条消息一定会覆盖它；没有 traceId 时会现场生成一个，
 * 因此不会把上一条的 traceId 带给无关消息。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(AbstractRabbitListenerContainerFactory.class)
public class MqConsumerTraceConfig {

    /**
     * 必须是 static 方法：BeanPostProcessor 要先于其它 bean 实例化，
     * 非 static 工厂方法会触发"过早初始化"告警（与 MqConfig 的处理一致）。
     */
    @Bean
    public static BeanPostProcessor rabbitListenerContainerTracePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractRabbitListenerContainerFactory) {
                    ((AbstractRabbitListenerContainerFactory<?>) bean)
                            .setAfterReceivePostProcessors(traceIdPostProcessor());
                }
                return bean;
            }
        };
    }

    private static MessagePostProcessor traceIdPostProcessor() {
        return message -> {
            Object traceId = message.getMessageProperties().getHeaders().get(TraceContext.HEADER);
            TraceContext.set(traceId == null ? TraceContext.generate() : String.valueOf(traceId));
            return message;
        };
    }
}
