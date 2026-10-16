package com.llmall.promotion.listener;

import com.llmall.common.exception.CommonException;
import com.llmall.promotion.config.RabbitConfig;
import com.llmall.promotion.mq.SeckillMessage;
import com.llmall.promotion.service.IVoucherService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀落库消费者：把 Redis 预扣成功的抢购真正写进数据库。
 *
 * 【注意】必须手动 ack，不能用默认自动 ack：自动 ack 在消息投递成功时就确认，业务尚未执行，
 * 应用中途崩溃会导致 Redis 名额已扣、券却永久丢失；手动 ack 把确认推迟到事务提交之后。
 * 失败按异常类型分流：{@link CommonException} 属业务性失败（重试无意义），其余属系统性失败可重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillOrderListener {

    private final IVoucherService voucherService;

    @RabbitListener(queues = RabbitConfig.SECKILL_ORDER_QUEUE)
    public void onSeckillOrder(SeckillMessage message, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                               @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered)
            throws IOException {
        String token = message == null ? null : message.getToken();
        Long voucherId = message == null ? null : message.getVoucherId();
        try {
            Long userVoucherId = voucherService.handleSeckillOrder(message);
            if (userVoucherId == null) {
                // 重复投递：业务侧已幂等跳过，仍要 ack，否则消息会一直堆在队列里
                log.info("秒杀消息已处理过或被忽略，直接 ack: token={}", token);
            } else {
                voucherService.markSeckillSuccess(token, voucherId, userVoucherId);
            }
            channel.basicAck(deliveryTag, false);
        } catch (CommonException e) {
            // 业务性失败不回队列（只会无限重投），把原因写给前端轮询。
            // 付费券在抢购请求时已扣款而券没发出去，必须退款，否则就是"钱收了、券没有"的直接资损
            // （退款本身按 token 幂等，见 consume_record）。
            log.warn("秒杀落库业务失败，不再重试: token={}, reason={}", token, e.getMessage());
            voucherService.markSeckillFailed(token, voucherId, e.getMessage());
            if (message != null) {
                voucherService.refundSeckillPurchase(token, message.getVoucherId(), message.getUserId(),
                        "秒杀落库业务失败退款");
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            if (Boolean.TRUE.equals(redelivered)) {
                // 重投过一次仍然失败：放弃消息并回补预扣，保证 Redis 名额与 DB 状态最终一致
                log.error("秒杀落库重投后仍失败，放弃并回补预扣: token={}", token, e);
                voucherService.markSeckillFailed(token, voucherId, "系统繁忙，抢购失败，请稍后重试");
                if (message != null) {
                    voucherService.compensateSeckillDeduction(message.getVoucherId(), message.getUserId());
                    // 名额回补必须与退款成对出现，两者都按 token 幂等
                    voucherService.refundSeckillPurchase(token, message.getVoucherId(), message.getUserId(),
                            "秒杀落库持续失败退款");
                }
                channel.basicNack(deliveryTag, false, false);
            } else {
                // 首次失败：requeue 重投一次，给 DB 抖动一点自愈机会。此处不退款 —— 重投仍可能成功发券，
                // 退早了会变成"券发出去了但钱退回来了"。
                log.error("秒杀落库失败，将重投一次: token={}", token, e);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }
}
