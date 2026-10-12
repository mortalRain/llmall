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
 * <h3>为什么要手动 ack，而不是用默认的自动 ack</h3>
 * 自动 ack 在消息**投递成功时**就确认了，业务还没执行；一旦应用在业务执行中崩溃（本项目演示环境内存吃紧，重启很常见），
 * 消息已经被 broker 删除，这次抢购就永久丢失，用户被扣了 Redis 名额却拿不到券。
 * 手动 ack 把确认推迟到"事务已提交"之后，才能保证"要么券真的到手，要么消息还在队列里"。
 *
 * <h3>失败怎么处理（重试与停止重试的分界）</h3>
 * <ul>
 *   <li><b>业务性失败</b>（{@link CommonException}：库存不足、已持有该券）：重试一万次也不会成功，
 *       直接 ack 并把失败原因写进 Redis 结果标记，用户轮询就能看到明确提示；</li>
 *   <li><b>系统性失败</b>（DB 连接抖动、死锁等运行时异常）：先 requeue 重投一次；
 *       若还是失败（消息被标记为 redelivered），则放弃重投并**回补 Redis 预扣**，
 *       避免"名额被永久占用"这种需要人工介入的脏状态。</li>
 * </ul>
 * 注意：消费者已经做了幂等（consume_record 唯一键），所以这里"重投"是安全操作；
 * 反过来，如果业务不幂等，就只能用死信队列做人工兜底 —— 两者是配套的。
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
                // 重复投递：业务侧已幂等跳过，这里仍然要 ack，否则消息会一直堆在队列里
                log.info("秒杀消息已处理过或被忽略，直接 ack: token={}", token);
            } else {
                voucherService.markSeckillSuccess(token, voucherId, userVoucherId);
            }
            channel.basicAck(deliveryTag, false);
        } catch (CommonException e) {
            // 业务性失败：不回队列（回队列只会无限重投），把原因写给前端轮询。
            // Z3：付费券在"抢购请求"时就已经扣过款，而券没发出去 —— 必须把钱退回，
            // 否则就是"钱收了、券没有"的直接资损。退款本身按 token 幂等（consume_record）。
            log.warn("秒杀落库业务失败，不再重试: token={}, reason={}", token, e.getMessage());
            voucherService.markSeckillFailed(token, voucherId, e.getMessage());
            if (message != null) {
                voucherService.refundSeckillPurchase(token, message.getVoucherId(), message.getUserId(),
                        "秒杀落库业务失败退款");
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            if (Boolean.TRUE.equals(redelivered)) {
                // 已经重投过一次仍然失败：判定为持续性故障，放弃消息并回补预扣，
                // 保证 Redis 名额与 DB 状态最终一致（宁可少卖，不能超卖/占名额不给券）
                log.error("秒杀落库重投后仍失败，放弃并回补预扣: token={}", token, e);
                voucherService.markSeckillFailed(token, voucherId, "系统繁忙，抢购失败，请稍后重试");
                if (message != null) {
                    voucherService.compensateSeckillDeduction(message.getVoucherId(), message.getUserId());
                    // Z3：名额回补的同时把券款退回去（幂等），两者必须成对出现
                    voucherService.refundSeckillPurchase(token, message.getVoucherId(), message.getUserId(),
                            "秒杀落库持续失败退款");
                }
                channel.basicNack(deliveryTag, false, false);
            } else {
                // 首次失败：requeue 重投一次，给 DB 抖动一点自愈机会。
                // 这里**不**退款：重投还有可能成功发券，退早了会导致"券发出去了但钱退回来了"。
                log.error("秒杀落库失败，将重投一次: token={}", token, e);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }
}
