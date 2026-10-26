package com.llmall.pay.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.llmall.api.client.TradeClient;
import com.llmall.api.client.UserClient;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.pay.domain.dto.PayApplyDTO;
import com.llmall.pay.domain.po.PayOrder;
import com.llmall.pay.mapper.PayOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请支付单（{@code POST /pay-orders}）的两条不变量：
 *
 * 1. 远程查单必须发生在任何数据库访问之前，且不得处于事务中（事务意味着一条连接被整段占住）；
 * 2. 并发重复申请只产生一条支付单，并且都返回同一个 id —— 由唯一键 + 单条 upsert 保证。
 *
 * 【为什么用 Mockito 而不是起 Spring 容器】这两条都是"调用顺序 / 主键回填"级别的约束，
 * 与 Spring 装配无关；真库并发语义由 MallE2E 与手工并发脚本覆盖（见 docs/P-01-…）。
 * 这里把 Mapper 当假实现，能精确断言"Feign 时还没碰过 Mapper"和"命中唯一键时不新增行"。
 */
@ExtendWith(MockitoExtension.class)
class PayOrderServiceImplIdempotencyTest {

    private static final long BIZ_ORDER_NO = 1001L;
    private static final int AMOUNT = 25600;

    @Mock
    private PayOrderMapper payOrderMapper;
    @Mock
    private UserClient userClient;
    @Mock
    private TradeClient tradeClient;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private BizMetrics metrics;
    @Mock
    private StringRedisTemplate redisTemplate;

    private PayOrderServiceImpl payOrderService;

    @BeforeEach
    void setUp() {
        payOrderService = new PayOrderServiceImpl(userClient, tradeClient, rabbitTemplate, metrics, redisTemplate);
        // ServiceImpl 的 baseMapper 由 Spring 反射注入；单测里手工塞入 Mock
        ReflectionTestUtils.setField(payOrderService, "baseMapper", payOrderMapper);
    }

    @Test
    @DisplayName("先查交易订单、后访问数据库；Feign 期间没有活动事务")
    void queriesTradeOrderBeforeTouchingDatabase() {
        AtomicBoolean databaseTouched = new AtomicBoolean(false);
        when(tradeClient.queryOrderById(BIZ_ORDER_NO)).thenAnswer(invocation -> {
            // 断言点 1：Feign 执行时还没有任何事务（有事务就说明连接已经被事务持有）
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Feign 查单不允在事务内执行");
            // 断言点 2：Feign 执行时还没有碰过 Mapper
            assertFalse(databaseTouched.get(), "数据库访问必须晚于交易订单校验");
            return payableOrder();
        });
        when(payOrderMapper.saveOrGetExisting(any(PayOrder.class))).thenAnswer(invocation -> {
            databaseTouched.set(true);
            // 模拟"新插入"：驱动回填的主键就是调用方预生成的那个 id，方法体不做任何改写
            return 1;
        });

        String payOrderId = payOrderService.applyPayOrder(applyDTO());

        assertTrue(Long.parseLong(payOrderId) > 0, "必须返回可用的支付单 id");
        InOrder order = inOrder(tradeClient, payOrderMapper);
        order.verify(tradeClient).queryOrderById(BIZ_ORDER_NO);
        order.verify(payOrderMapper).saveOrGetExisting(any(PayOrder.class));
        verify(payOrderMapper, never()).insert(any(PayOrder.class));
    }

    @Test
    @DisplayName("唯一键命中时复用已有支付单：不新增行，返回同一个 id")
    void reusesExistingPayOrderWhenUniqueKeyHit() {
        long existingId = 777L;
        when(tradeClient.queryOrderById(BIZ_ORDER_NO)).thenReturn(payableOrder());
        when(payOrderMapper.saveOrGetExisting(any(PayOrder.class))).thenAnswer(invocation -> {
            // 模拟 ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)：主键被改写成已有行 id
            PayOrder candidate = invocation.getArgument(0);
            candidate.setId(existingId);
            return 1;
        });
        when(payOrderMapper.selectOne(any(Wrapper.class))).thenReturn(existingPayOrder(existingId, 1));

        String payOrderId = payOrderService.applyPayOrder(applyDTO());

        assertEquals(String.valueOf(existingId), payOrderId, "重复申请必须收敛到同一条支付单");
        verify(payOrderMapper, never()).insert(any(PayOrder.class));
        // upsert 只执行一次：既没有"先查一次再插一次"，也没有失败重试
        verify(payOrderMapper, times(1)).saveOrGetExisting(any(PayOrder.class));
    }

    @Test
    @DisplayName("已支付成功的交易订单不允许再次申请支付单")
    void rejectsTerminalPayOrder() {
        long existingId = 778L;
        when(tradeClient.queryOrderById(BIZ_ORDER_NO)).thenReturn(payableOrder());
        when(payOrderMapper.saveOrGetExisting(any(PayOrder.class))).thenAnswer(invocation -> {
            PayOrder candidate = invocation.getArgument(0);
            candidate.setId(existingId);
            return 1;
        });
        when(payOrderMapper.selectOne(any(Wrapper.class))).thenReturn(existingPayOrder(existingId, 3));

        BizIllegalException error = assertThrows(BizIllegalException.class,
                () -> payOrderService.applyPayOrder(applyDTO()));
        assertEquals("订单已经支付！", error.getMessage());
    }

    private PayApplyDTO applyDTO() {
        return PayApplyDTO.builder()
                .bizOrderNo(BIZ_ORDER_NO)
                .amount(AMOUNT)
                .payChannelCode("balance")
                .payType(5)
                .orderInfo("unit-test")
                .build();
    }

    private TradeOrderDTO payableOrder() {
        TradeOrderDTO order = new TradeOrderDTO();
        order.setId(BIZ_ORDER_NO);
        order.setStatus(1);
        order.setTotalFee(AMOUNT);
        return order;
    }

    private PayOrder existingPayOrder(long id, int status) {
        PayOrder order = new PayOrder();
        order.setId(id);
        order.setBizOrderNo(BIZ_ORDER_NO);
        order.setPayChannelCode("balance");
        order.setAmount(AMOUNT);
        order.setStatus(status);
        return order;
    }
}
