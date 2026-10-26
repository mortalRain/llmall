package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.llmall.api.client.ItemClient;
import com.llmall.api.client.PayClient;
import com.llmall.api.client.ShopClient;
import com.llmall.api.client.UserClient;
import com.llmall.api.client.VoucherClient;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.common.utils.UserContext;
import com.llmall.trade.domain.dto.OrderVerifyDTO;
import com.llmall.trade.domain.po.MessageLog;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.mapper.OrderMapper;
import com.llmall.trade.mq.OrderEventFactory;
import com.llmall.trade.service.IOrderDetailService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 核销越权修复的回归测试：{@code verifyOrder} 必须在核销前确认"调用者是该订单门店的员工"，
 * 且门店服务不可用时按拒绝处理（fail-closed）。
 *
 * 【为什么是纯 Mockito 单测而不是 @SpringBootTest】
 * 1) 要验证的核心是"调用顺序与拒绝路径"，不是 SQL：mock 掉 baseMapper 就能精确断言
 *    "非员工时一句 UPDATE 都没发、一条 MQ 消息都没投"——真实库里只能看到最终状态，看不到这两点；
 * 2) @SpringBootTest 需要真实 MySQL/Nacos/MQ。本仓库的集成测试统一标 {@code @Tag("integration")}
 *    默认不跑，若把安全守卫写成集成测试，CI 上等于没测。
 *
 * 【注意】{@code LambdaQueryChainWrapper} 需要 MyBatis-Plus 的 TableInfo 缓存才能把
 * {@code Order::getVerifyCode} 解析成列名，而纯单测没有 MyBatis 启动流程，故在 {@link #initTableInfo()}
 * 里手工初始化一次（等价于 MP 启动时做的事），不是"改测试迁就实现"。
 */
@ExtendWith(MockitoExtension.class)
class VerifyOrderStaffGuardTest {

    /** 调用者（登录用户） */
    private static final long OPERATOR = 1L;

    /** 订单所属门店（也是核销门店） */
    private static final long SHOP_ID = 10L;

    private static final long ORDER_ID = 1001L;

    private static final String VERIFY_CODE = "ACDEFGHJ";

    @Mock
    private ItemClient itemClient;
    @Mock
    private IOrderDetailService detailService;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private IMessageLogService<MessageLog> messageLogService;
    @Mock
    private OrderEventFactory orderEventFactory;
    @Mock
    private VoucherClient voucherClient;
    @Mock
    private UserClient userClient;
    @Mock
    private PayClient payClient;
    @Mock
    private ShopClient shopClient;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OrderMapper orderMapper;

    private OrderServiceImpl orderService;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测里没有 MyBatis 启动流程，lambda 列名解析依赖的 TableInfo 缓存需要手工建一次
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(itemClient, detailService, rabbitTemplate, messageLogService,
                orderEventFactory, voucherClient, userClient, payClient, shopClient, transactionTemplate);
        // ServiceImpl 的 baseMapper 是父类字段（正常由 @Autowired 注入），单测里直接塞 mock
        ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);
        // 订单归属人与调用者不是同一个人：核销是"门店替顾客核销"，不要求调用者是订单归属人
        when(orderMapper.selectList(any())).thenReturn(Collections.singletonList(paidOrder()));
        UserContext.setUser(OPERATOR);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    @DisplayName("非本店员工核销 → 403 拒绝，且不写库、不发核销事件")
    void nonStaffIsRejectedWithoutSideEffects() {
        when(shopClient.isStaff(SHOP_ID, OPERATOR)).thenReturn(false);

        assertThatThrownBy(() -> orderService.verifyOrder(verifyDTO()))
                .as("非员工必须被拒绝")
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("仅门店员工可核销");

        // 核心断言：拒绝必须发生在写库之前 —— 否则订单已经是"已核销"、顾客的券被吞掉
        verify(orderMapper, never()).update(any(), any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("本店员工核销 → 放行，状态更新为已核销并投递核销事件")
    void staffCanVerifyOrder() {
        when(shopClient.isStaff(SHOP_ID, OPERATOR)).thenReturn(true);
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        Long verifiedOrderId = orderService.verifyOrder(verifyDTO());

        assertThat(verifiedOrderId).isEqualTo(ORDER_ID);
        // 状态 2 → 7 的条件更新只发一次；affected rows = 1 是成功判据
        verify(orderMapper).update(isNull(), any());
        verify(rabbitTemplate).convertAndSend(eq(MqConstants.TRADE_EXCHANGE),
                eq(MqConstants.ORDER_VERIFIED_KEY), any(Object.class));
    }

    @Test
    @DisplayName("门店服务不可用（判权查询抛异常）→ 按拒绝处理，不能 fail-open")
    void shopServiceUnavailableFailsClosed() {
        // 与 ShopClientFallback#isStaff 的降级行为一致：抛异常而不是返回猜测的布尔值
        when(shopClient.isStaff(SHOP_ID, OPERATOR))
                .thenThrow(new BizIllegalException("门店服务不可用，无法校验核销权限（按拒绝处理）"));

        assertThatThrownBy(() -> orderService.verifyOrder(verifyDTO()))
                .as("门店服务不可用时必须拒绝核销")
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("稍后重试");

        verify(orderMapper, never()).update(any(), any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("Sentinel 把降级异常包成 AssertionError 时仍按拒绝处理")
    void sentinelWrappedAssertionErrorFailsClosed() {
        // 现场实测：feign.sentinel.enabled=true 时，fallbackFactory 的降级方法抛出的 RuntimeException
        // 会被 SentinelInvocationHandler 包成 AssertionError（Error 不是 Exception），
        // 若只 catch(Exception) 就会漏掉 → 变成 400 "请求参数处理异常"。
        when(shopClient.isStaff(SHOP_ID, OPERATOR))
                .thenThrow(new AssertionError(
                        "com.llmall.common.exception.BizIllegalException: 门店服务不可用，无法校验核销权限（按拒绝处理）"));

        assertThatThrownBy(() -> orderService.verifyOrder(verifyDTO()))
                .as("降级异常被包装后仍必须给出明确的 403")
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("稍后重试");

        verify(orderMapper, never()).update(any(), any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("核销门店与订单门店不一致 → 403，且不查询员工身份")
    void shopMismatchIsRejectedBeforeStaffLookup() {
        OrderVerifyDTO dto = verifyDTO();
        dto.setShopId(SHOP_ID + 1);

        assertThatThrownBy(() -> orderService.verifyOrder(dto))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("核销码不属于该门店");

        // 门店都不匹配就不再打门店服务：少一次跨服务调用，也避免用错误门店泄露员工关系
        verifyNoInteractions(shopClient);
        verify(orderMapper, never()).update(any(), any());
    }

    /** 已付款待使用的门店券订单：调用者与归属人不同，用于确认判权只看"是否本店员工" */
    private Order paidOrder() {
        return new Order()
                .setId(ORDER_ID)
                .setUserId(OPERATOR + 100)
                .setShopId(SHOP_ID)
                .setStatus(Order.STATUS_PAID)
                .setVerifyCode(VERIFY_CODE)
                .setOrderType(2);
    }

    private OrderVerifyDTO verifyDTO() {
        OrderVerifyDTO dto = new OrderVerifyDTO();
        dto.setVerifyCode(VERIFY_CODE);
        dto.setShopId(SHOP_ID);
        return dto;
    }
}
