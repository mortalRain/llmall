package com.llmall.common.mq;

import com.llmall.common.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 死信投递：把"重试耗尽"的消息从原队列搬到死信交换机，并带上排查所需的信息。
 *
 * <p>【它解决的两个真实问题】（改前实测：往 {@code user.points.order.paid.queue} 发一条非法 JSON，
 * 消费端转换失败后消息**当场消失**，队列深度回到 0，只在服务日志里留一段栈 —— 消息就这么没了。）
 * <ol>
 *   <li><b>静默丢弃</b>：没有死信路由时，重试耗尽的消息被 reject 后就永久消失，业务侧表现为
 *       "积分没发、销量没加、搜索索引没更新"，而且没有任何信号；</li>
 *   <li><b>满速热循环</b>：{@code default-requeue-rejected} 为 true（Spring Boot 默认）时，
 *       失败消息会被立刻 requeue 回队列，消费者马上又拿到它 —— CPU 打满、日志刷屏，
 *       真正的新消息排在后面进不来。</li>
 * </ol>
 *
 * <p>【为什么不用现成的 RepublishMessageRecoverer】它的路由键取"原消息的路由键"（如 {@code order.paid}），
 * 而多个服务可能订阅同一个路由键，死信会串到别人的队列里；这里改成按 **原队列名 + .dead**
 * 路由（{@code messageProperties.consumerQueue} 是消费端才有的、最精确的来源），
 * 于是"谁的消息进谁的死信队列"，排查时不用在 6 个队列里找同一条消息。
 *
 * <p>【防循环】被搬过一次的消息会带上 {@code x-dead-lettered=true}：再次失败时只告警不再搬运，
 * 否则死信队列自己的消费失败又会产生新的死信，形成新的循环。
 */
public final class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    /** 死信交换机（topic）：所有服务的失败消息都汇到这里，再按路由键分发到各自的死信队列。 */
    public static final String DEAD_LETTER_EXCHANGE = "llmall.dlx";

    /** 死信队列名后缀：{@code <服务名>.dead.letter.queue} —— 每个服务一个，谁的消息谁处理。 */
    public static final String DEAD_LETTER_QUEUE_SUFFIX = ".dead.letter.queue";

    /** 死信路由键后缀：{@code <原队列名>.dead}。 */
    public static final String DEAD_LETTER_ROUTING_KEY_SUFFIX = ".dead";

    /** 搬运标记：已有该头的消息不再二次搬运（防死信循环）。 */
    public static final String HEADER_DEAD_LETTERED = "x-dead-lettered";

    private DeadLetterPublisher() {
    }

    /**
     * 把一条消费失败的消息搬到死信队列。
     *
     * @param rabbitTemplate 发送用模板（复用容器里那一个，连接池与序列化口径都一致）
     * @param message        原始消息（RabbitMQ 传来的那一条）
     * @param originalQueue  原队列名；取不到时用 "unknown"（消息仍会被保留，不至于丢）
     * @param cause          最后一次失败的原因
     * @return true 表示已成功搬运
     */
    public static boolean publish(RabbitTemplate rabbitTemplate, Message message, String originalQueue, Throwable cause) {
        MessageProperties original = message.getMessageProperties();
        String queue = (originalQueue == null || originalQueue.isEmpty()) ? "unknown" : originalQueue;
        String routingKey = queue + DEAD_LETTER_ROUTING_KEY_SUFFIX;

        MessageProperties out = new MessageProperties();
        // 只保留"业务相关"的属性：contentType 决定下游能否反序列化，messageId/timestamp 用于对账，
        // 其余（deliveryTag / consumerQueue / redelivered / receivedXxx）都是上一个消费者的会话信息，
        // 原样带回去会让死信消息看起来像"已经被投递过好几手"，干扰后续排查。
        out.setContentType(original.getContentType());
        out.setContentEncoding(original.getContentEncoding());
        out.setMessageId(original.getMessageId());
        out.setTimestamp(new java.util.Date());
        out.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // 业务自定义头全部保留：其中就有全链路 traceId（C5），死信也能追到最初那次请求
        if (original.getHeaders() != null) {
            out.getHeaders().putAll(original.getHeaders());
        }
        out.setHeader(HEADER_DEAD_LETTERED, true);
        out.setHeader("x-original-queue", queue);
        out.setHeader("x-original-exchange", String.valueOf(original.getReceivedExchange()));
        out.setHeader("x-original-routing-key", String.valueOf(original.getReceivedRoutingKey()));
        out.setHeader("x-exception-message", cause == null ? null : cause.getMessage());
        out.setHeader("x-exception-class", cause == null ? null : cause.getClass().getName());
        out.setHeader("x-exception-stacktrace", stackTrace(cause));

        try {
            rabbitTemplate.send(DEAD_LETTER_EXCHANGE, routingKey, new Message(message.getBody(), out));
            log.error("消息重试耗尽，已转入死信队列（需人工或补偿流程处理）: 原队列={} 死信路由键={} traceId={} 原因={}",
                    queue, routingKey, traceIdOf(original), cause == null ? "未知" : cause.toString());
            return true;
        } catch (Exception e) {
            // 搬运失败也不能把原始异常吞掉：这里只补一条 ERROR，原始失败仍由调用方抛出/记录，
            // 否则"死信都没进"这件事会被掩盖成"消息处理过了"。
            log.error("消息重试耗尽但转入死信队列失败（消息将随原队列的 reject 一起丢失，请检查 MQ 与 llmall.dlx）: 原队列={} traceId={}",
                    queue, traceIdOf(original), e);
            return false;
        }
    }

    /** 是否已经是死信消息（防二次搬运）。 */
    public static boolean alreadyDeadLettered(MessageProperties properties) {
        Object flag = properties.getHeaders() == null ? null : properties.getHeaders().get(HEADER_DEAD_LETTERED);
        return Boolean.TRUE.equals(flag) || "true".equals(String.valueOf(flag));
    }

    private static String traceIdOf(MessageProperties properties) {
        Object traceId = properties.getHeaders() == null ? null : properties.getHeaders().get(TraceContext.HEADER);
        return traceId == null ? "-" : String.valueOf(traceId);
    }

    private static String stackTrace(Throwable cause) {
        if (cause == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();
        // 截断：MQ 消息头有大小上限，完整栈对排查前期意义不大（日志里已经有完整栈）
        return text.length() > 4000 ? text.substring(0, 4000) + "...(truncated)" : text;
    }
}
