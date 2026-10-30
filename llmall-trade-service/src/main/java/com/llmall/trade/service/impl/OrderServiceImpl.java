package com.llmall.trade.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.client.ItemClient;
import com.llmall.api.client.PayClient;
import com.llmall.api.client.ShopClient;
import com.llmall.api.client.UserClient;
import com.llmall.api.client.VoucherClient;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.api.dto.UserVoucherDTO;
import com.llmall.api.event.CartClearEvent;
import com.llmall.api.event.OrderPaidEvent;
import com.llmall.api.event.OrderVerifiedEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.trade.domain.dto.OrderFormDTO;
import com.llmall.trade.domain.dto.OrderVerifyDTO;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.domain.po.OrderDetail;
import com.llmall.trade.domain.vo.OrderDetailVO;
import com.llmall.trade.domain.vo.OrderVO;
import com.llmall.trade.mapper.OrderMapper;
import com.llmall.trade.mq.OrderEventFactory;
import com.llmall.trade.service.IOrderDetailService;
import com.llmall.common.mq.IMessageLogService;
import com.llmall.trade.domain.po.MessageLog;
import com.llmall.trade.service.IOrderService;
import com.llmall.trade.support.OrderInputPolicy;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private static final int ORDER_TYPE_PHYSICAL = 1;
    private static final int ORDER_TYPE_VOUCHER = 2;
    private static final int ITEM_TYPE_PACKAGE = 2;

    /** 核销码字母表：剔除 0/O、1/I/L、2/Z、5/S、8/B 等易混字符，避免用户念错码；长度 8 位，SecureRandom 生成 */
    private static final char[] VERIFY_CODE_ALPHABET = "ACDEFGHJKMNPQRTUVWXY34679".toCharArray();
    private static final int VERIFY_CODE_LENGTH = 8;
    private static final SecureRandom VERIFY_CODE_RANDOM = new SecureRandom();

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final RabbitTemplate rabbitTemplate;
    private final IMessageLogService<MessageLog> messageLogService;
    private final OrderEventFactory orderEventFactory;
    private final VoucherClient voucherClient;
    private final UserClient userClient;
    private final PayClient payClient;
    private final ShopClient shopClient;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Value("${llmall.trade.order-timeout-seconds:900}")
    private long orderTimeoutSeconds;

    /**
     * Seata AT 开关（默认 false）：开启时跨服务扣库存是全局事务的一个分支，回滚由 TC 用 undo_log 完成，
     * 本地再调一次 restoreStock 会变成回滚两次、库存凭空变多。
     */
    @org.springframework.beans.factory.annotation.Value("${seata.enabled:false}")
    private boolean seataEnabled;

    /**
     * 故障注入开关（默认 false）：扣库存成功后人为失败，用于触发"库存已扣 → 本地事务回滚 → 自动回补库存"这条路径。
     * 【注意】该路径在正常业务中不会自然出现，生产必须保持 false。
     */
    @org.springframework.beans.factory.annotation.Value("${llmall.trade.fault-injection.after-deduct:false}")
    private boolean faultInjectAfterDeduct;

    /**
     * 下单主链路：入参校验 → 查商品算价 → 落订单 → 扣库存 → 锁券 → 清购物车 → 发延迟消息；前四步失败由本地事务回滚兜住，不留订单。
     * 锁券发生在事务提交之后、无法回滚，失败只能显式补偿（关单 + 回滚库存）。
     * 金额单位一律为分；库存按 (biz_type, bizId, itemId)、券按 userVoucherId + orderId 幂等，重复调用不会重复扣减/锁定。
     */
    @Override
    // Seata AT 全局事务（下单 + 跨服务扣库存/锁券）；seata.enabled=false（默认）时注解不生效，方法退化为纯本地事务
    @GlobalTransactional(name = "llmall-create-order", rollbackFor = Exception.class)
    public Long createOrder(OrderFormDTO orderFormDTO) {
        Long userId = requireLogin();
        OrderFormDTO request = OrderInputPolicy.copyAndValidate(orderFormDTO);
        String clientToken = request.getClientToken();
        // 幂等回放：同一用户同一令牌已经下过单，直接返回原订单，不再重复扣库存/锁券
        if (clientToken != null) {
            Order exist = findByIdempotencyKey(userId, clientToken);
            if (exist != null) {
                return replayOrder(exist, request);
            }
        }
        try {
            return transactionTemplate.execute(status -> doCreateOrder(request, clientToken));
        } catch (DuplicateKeyException e) {
            // 并发提交同一令牌：唯一键 uk_user_token 裁决，输的一方回查并回放
            if (clientToken == null) {
                throw e;
            }
            Order exist = findByIdempotencyKey(userId, clientToken);
            if (exist == null) {
                throw e;
            }
            return replayOrder(exist, request);
        }
    }

    /**
     * 下单事务体：由事务模板包住，保证"业务数据 + 消息表"同事务。
     *
     * 【为什么把事务从方法注解改成模板】幂等冲突的回查必须发生在事务结束之后：
     * REPEATABLE READ 下，同一事务内的快照早于并发提交，捕获唯一键冲突后再查可能查不到那一行。
     */
    private Long doCreateOrder(OrderFormDTO orderFormDTO, String clientToken) {
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        int total = OrderInputPolicy.calculateTotal(items, itemNumMap);

        // 已校验所有商品同门店、同履约类型，第一件商品即可代表订单归属。
        Order order = new Order();
        order.setTotalFee(total);
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(Order.STATUS_UNPAID);
        order.setClientToken(clientToken);
        order.setRequestHash(clientToken == null ? null : orderRequestHash(orderFormDTO));
        ItemDTO firstItem = items.get(0);
        order.setShopId(firstItem.getShopId());
        order.setOrderType(ITEM_TYPE_PACKAGE == (firstItem.getType() == null ? 1 : firstItem.getType())
                ? ORDER_TYPE_VOUCHER : ORDER_TYPE_PHYSICAL);

        // 券抵扣必须在落库之前算完：total_fee 落库即用户实付，支付侧只做"金额相等"校验（单位：分）
        Long userVoucherId = orderFormDTO.getUserVoucherId();
        if (userVoucherId != null) {
            UserVoucherDTO voucher = queryUsableVoucher(userVoucherId, firstItem.getShopId());
            order.setTotalFee(OrderInputPolicy.payable(total, voucher.getActualValue()));
            order.setVoucherId(userVoucherId);
        }
        save(order);

        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 扣库存带业务号，库存服务据此记流水并做幂等（重复扣减被唯一键挡住）；失败抛异常回滚本地事务，不生成"无库存的订单"
        // 补偿钩子必须在真正扣库存之前登记：一旦响应丢失（扣了、回包没收到），本地事务回滚后订单不存在、库存却少了，详见 compensateDeductedStockAfterRollback
        registerStockCompensationOnRollback(order.getId(), detailDTOS);
        detailDTOS.forEach(dto -> dto.setBizType(MqConstants.BIZ_ORDER_CREATED)
                .setBizId(String.valueOf(order.getId()))
                .setShopId(order.getShopId()));
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new BizIllegalException("库存不足，下单失败", e);
        }
        if (faultInjectAfterDeduct) {
            throw new IllegalStateException("【故障注入】扣库存后人为失败（llmall.trade.fault-injection.after-deduct=true），"
                    + "用于验证事务回滚后的库存回补");
        }

        // 锁券（券 1 → 2 并绑定订单号）登记在本地事务提交之后执行，见 lockVoucherAfterCommit；
        // 顺序固定为 [先扣库存 → 提交订单 → 再锁券]：券是用户资产，只有订单真正落地后才允许锁，锁券失败则关单 + 回滚库存补偿
        if (userVoucherId != null) {
            lockVoucherAfterCommit(userVoucherId, order.getId());
        }

        // 清购物车走本地消息表（同事务登记 → 提交后投递 → 失败按退避重试）；该动作按 itemIds 删除、本身幂等，重投安全
        CartClearEvent event = new CartClearEvent(UserContext.getUser(), new ArrayList<>(itemIds));
        messageLogService.record(MqConstants.BIZ_CART_CLEAR, String.valueOf(order.getId()),
                MqConstants.TRADE_EXCHANGE, MqConstants.ORDER_CREATE_KEY, event);
        messageLogService.dispatchAfterCommit(MqConstants.BIZ_CART_CLEAR, String.valueOf(order.getId()));

        // 延迟消息用于超时关单，时长取 llmall.trade.order-timeout-seconds（默认 900 秒）
        // 【注意】必须 try/catch：此刻库存已扣、订单已提交，这里抛异常会把本地事务回滚成"订单不存在但库存少了"，且回滚后连订单号都没有、取消/对账都无从补偿；
        // 延迟消息只是"尽早关单"的优化，丢了还有 OrderTimeoutScanTask 定时扫描兜底，故吞掉并告警即可
        try {
            rabbitTemplate.convertAndSend(
                    MqConstants.DELAY_EXCHANGE,
                    MqConstants.DELAY_ORDER_KEY,
                    order.getId(), message -> {
                        message.getMessageProperties().setDelay((int) Math.min(orderTimeoutSeconds * 1000L, Integer.MAX_VALUE));
                        return message;
                    }
            );
        } catch (Exception e) {
            log.error("订单延迟消息发送失败（订单已生成，将由超时扫描任务兜底关单）：orderId={}", order.getId(), e);
        }
        return order.getId();
    }

    /** 按用户 + 客户端令牌查已存在的订单（唯一键 uk_user_token 的查询侧） */
    private Order findByIdempotencyKey(Long userId, String clientToken) {
        return lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(Order::getClientToken, clientToken)
                .one();
    }

    /**
     * 幂等回放：同令牌同参数返回原订单；同令牌不同参数说明客户端复用了令牌，必须拒绝。
     */
    private Long replayOrder(Order exist, OrderFormDTO orderFormDTO) {
        String hash = orderRequestHash(orderFormDTO);
        if (exist.getRequestHash() != null && !exist.getRequestHash().equals(hash)) {
            throw new BadRequestException("同一幂等令牌不能用于不同的下单参数");
        }
        log.info("下单幂等回放，返回已存在订单: orderId={}", exist.getId());
        return exist.getId();
    }

    /**
     * 下单参数哈希：覆盖影响订单内容与金额的字段，商品行排序后拼接以保证"顺序无关"。
     */
    private static String orderRequestHash(OrderFormDTO orderFormDTO) {
        StringBuilder sb = new StringBuilder();
        sb.append(orderFormDTO.getAddressId()).append('|')
                .append(orderFormDTO.getPaymentType()).append('|')
                .append(orderFormDTO.getUserVoucherId()).append('|');
        if (orderFormDTO.getDetails() != null) {
            orderFormDTO.getDetails().stream()
                    .filter(Objects::nonNull)
                    .map(detail -> detail.getItemId() + ":" + detail.getNum())
                    .sorted()
                    .forEach(item -> sb.append(item).append(';'));
        }
        return DigestUtil.sha256Hex(sb.toString());
    }

    /**
     * 登记"下单事务没提交就把库存补回来"的钩子：必须在真正扣库存之前调用。
     *
     * 兜的是跨服务调用丢回包的场景：库存侧已扣成功而回包丢失/超时，本地事务回滚后会留下"订单不存在、库存少了"且无人可查的少卖。
     * 用 afterCompletion 而非 catch：提交阶段失败（status=UNKNOWN）时方法已正常返回，catch 不会执行，判据必须是不等于 COMMITTED。
     * 【注意】Seata 开启时必须跳过：扣库存是全局事务分支，回滚由 TC 依据 undo_log 完成，这里再补一次就是回滚两次、库存凭空变多。
     */
    private void registerStockCompensationOnRollback(Long orderId, List<OrderDetailDTO> details) {
        if (seataEnabled) {
            log.debug("Seata AT 已开启，库存回滚交给全局事务（TC 用 undo_log 反向补偿），本地不再重复补: orderId={}", orderId);
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("事务同步未激活，库存补偿钩子未注册（下单若失败需人工核对库存）: orderId={}", orderId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                compensateDeductedStockAfterRollback(orderId, details, status);
            }
        });
    }

    /**
     * 事务未提交时的库存核对与补偿：先向库存服务确认该业务号是否真的扣过，扣了才补。
     *
     * 两个方向的代价不对称："没扣却补"会凭空多出库存（超卖），"扣了没补"只是少卖且这里会打 ERROR 让人介入。
     * 故偏保守：查询失败（拿不到结论）时不补，只报警。
     */
    private void compensateDeductedStockAfterRollback(Long orderId, List<OrderDetailDTO> details, int status) {
        String bizId = String.valueOf(orderId);
        try {
            if (!itemClient.isStockDeducted(bizId, MqConstants.BIZ_ORDER_CREATED)) {
                log.info("下单事务未提交（status={}），库存未扣减，无需补偿: orderId={}", status, orderId);
                return;
            }
            // 回滚用独立业务类型 ORDER_CANCELED：与扣减的 ORDER_CREATED 分开记流水，
            // 这样"扣了又补"是一对可对账的记录，而不会被幂等键互相顶掉
            details.forEach(dto -> dto.setBizType(MqConstants.BIZ_ORDER_CANCELED).setBizId(bizId));
            itemClient.restoreStock(details);
            log.warn("下单事务未提交（status={}）但库存已扣减，已自动回补库存（避免少卖）: orderId={}", status, orderId);
        } catch (Exception e) {
            log.error("【需人工介入】下单事务未提交且库存补偿失败，可能少卖: orderId={}, 明细={}", orderId, details, e);
        }
    }

    /** 支付成功回调：条件更新 1 → 2 并在同一本地事务内写本地消息表、提交后立刻投递；重复回调 affected rows = 0 即幂等，投递失败由重试/对账兜底 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markOrderPaySuccess(Long orderId) {
        // 核销码在支付成功这一刻生成，并与状态变更写在同一条 UPDATE 里：未付款订单不该有可到店使用的凭证，分开写会出现"status=2 但没有核销码"的订单
        String verifyCode = generateVerifyCode();
        boolean updated = lambdaUpdate()
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, Order.STATUS_UNPAID)
                .set(Order::getStatus, Order.STATUS_PAID)
                .set(Order::getPayTime, LocalDateTime.now())
                .set(Order::getVerifyCode, verifyCode)
                .update();
        if (!updated) {
            log.info("订单状态不是待支付或已处理过，忽略本次支付通知: orderId={}", orderId);
            return;
        }
        log.info("订单支付成功，核销码已生成: orderId={}, verifyCode={}", orderId, verifyCode);
        Order order = getById(orderId);
        OrderPaidEvent event = orderEventFactory.buildPaidEvent(order);
        messageLogService.record(MqConstants.BIZ_ORDER_PAID, String.valueOf(orderId),
                MqConstants.TRADE_EXCHANGE, MqConstants.ORDER_PAID_KEY, event);
        messageLogService.dispatchAfterCommit(MqConstants.BIZ_ORDER_PAID, String.valueOf(orderId));
        log.info("订单支付成功已受理: orderId={}, 消息已登记并将在提交后投递", orderId);
    }

    @Override
    public void cancelOrder(Long orderId) {
        Order order = getById(orderId);
        if (order == null) throw new BadRequestException("订单不存在");
        if (Integer.valueOf(Order.STATUS_CANCELED).equals(order.getStatus())) return;
        if (Integer.valueOf(Order.STATUS_CANCELING).equals(order.getStatus())) {
            recoverCompensation(orderId);
            return;
        }
        if (!Integer.valueOf(Order.STATUS_UNPAID).equals(order.getStatus())) {
            throw new BizIllegalException("订单已支付或状态不允许取消");
        }
        com.llmall.api.dto.TradeOrderDTO snapshot = new com.llmall.api.dto.TradeOrderDTO();
        snapshot.setId(orderId);
        snapshot.setUserId(order.getUserId());
        snapshot.setTotalFee(order.getTotalFee());
        com.llmall.api.dto.PayOrderStatusDTO closed = payClient.closeUnpaid(snapshot);
        if (closed == null || !closed.isDetermined() || !Integer.valueOf(2).equals(closed.getPayStatus())) {
            throw new BizIllegalException("支付处理中或已完成，暂不能取消订单");
        }
        boolean accepted = localTransition(orderId, Order.STATUS_UNPAID, Order.STATUS_CANCELING);
        if (!accepted) throw new BizIllegalException("订单状态已变更，请刷新后重试");
        try { recoverCompensation(orderId); }
        catch (RuntimeException | AssertionError e) { log.error("取消已受理，补偿任务将继续恢复: orderId={}", orderId, e); }
    }

    private boolean localTransition(Long orderId, int from, int to) {
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionTemplate.getTransactionManager());
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(tx.execute(status -> lambdaUpdate().eq(Order::getId, orderId)
                .eq(Order::getStatus, from).set(Order::getStatus, to)
                .set(Order::getUpdateTime, LocalDateTime.now())
                .set(to == Order.STATUS_CANCELED, Order::getCloseTime, LocalDateTime.now()).update()));
    }

    /** 断言订单未被核销：把"核销后不得取消/退款"这条业务不变量写成硬约束，供所有会动到钱或券的路径入口复用 */
    private void assertNotVerified(Order order, String action) {
        if (order.getVerifyTime() != null || (order.getStatus() != null && order.getStatus() == Order.STATUS_VERIFIED)) {
            throw new BizIllegalException("订单已核销，不允许" + action);
        }
    }

    /**
     * 回滚库存：必须走专用 restoreStock（正数数量 + 幂等业务号 + 销量兜底），不可复用"扣减库存"接口。
     * @param bizType 取消关单用 ORDER_CANCELED，退款用 ORDER_REFUNDED；库存流水按 (biz_type, bizId, itemId) 幂等，两条路径的幂等互不干扰。
     */
    private void recoverStock(Order order, String bizType) {
        List<OrderDetail> orderDetails = detailService.listByOrderId(order.getId());
        if (orderDetails == null || orderDetails.isEmpty()) {
            throw new BadRequestException("订单详情不存在！");
        }
        List<OrderDetailDTO> recoverItems = orderDetails.stream()
                .map(detail -> new OrderDetailDTO()
                        .setItemId(detail.getItemId())
                        .setNum(detail.getNum())
                        .setBizType(bizType)
                        .setBizId(String.valueOf(order.getId()))
                        .setShopId(order.getShopId()))
                .collect(Collectors.toList());
        try {
            itemClient.restoreStock(recoverItems);
        } catch (Exception e) {
            throw new BizIllegalException("恢复库存失败！", e);
        }
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }

    /** 我的订单分页：必须按 userId 过滤，绝不返回他人订单；明细一次 IN 查询后内存分组，避免 N 个订单打 N 次库 */
    @Override
    public PageDTO<OrderVO> queryMyOrders(PageQuery query, Integer status) {
        Long userId = requireLogin();
        Page<Order> page = query.toMpPage("create_time", false);
        page(page, new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(status != null, Order::getStatus, status));

        List<Order> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        List<Long> orderIds = records.stream().map(Order::getId).collect(Collectors.toList());
        Map<Long, List<OrderDetail>> detailMap = detailService.lambdaQuery()
                .in(OrderDetail::getOrderId, orderIds)
                .list()
                .stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderId));

        List<OrderVO> list = records.stream()
                .map(order -> toOrderVO(order, detailMap.get(order.getId())))
                .collect(Collectors.toList());
        return PageDTO.of(page, list);
    }

    @Override
    public OrderVO queryOrderDetail(Long orderId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        // 归属校验 fail-closed：订单 VO 带核销码（到店出示即可核销的凭据），身份为空必须拒绝，否则等于给直连服务端口的调用开后门
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new ForbiddenException("无权查看该订单");
        }
        if (!userId.equals(order.getUserId())) {
            throw new ForbiddenException("无权查看他人订单");
        }
        return toOrderVO(order, detailService.listByOrderId(orderId));
    }

    @Override
    public void cancelMyOrder(Long orderId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        if (!requireLogin().equals(order.getUserId())) {
            throw new ForbiddenException("无权操作他人订单");
        }
        cancelOrder(orderId);
    }

    /**
     * 到店核销：status 2（已付款待使用）→ 7（已核销），并发重复核销靠条件更新 WHERE status = 2 由数据库裁决（第二次 affected rows = 0），比"先查再 update"可靠。
     * verify_code 上没有唯一索引：同码命中多单说明数据已不可信，宁可拒绝核销也不取第一条（核销错了要按错的订单退款、给错的门店结算）。
     *
     * 【权限】必须是该订单所属门店的在岗员工（修复前的实现只校验登录 + 门店一致，任何登录用户拿到核销码
     * 都能核销别人的订单）。员工身份由门店域回答（ShopClient#isStaff），交易域不复制员工表；
     * 判定失败/下游不可用一律按拒绝处理，见 {@link #assertStaffOfOrderShop}。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long verifyOrder(OrderVerifyDTO verifyDTO) {
        Long operator = requireLogin();
        if (verifyDTO == null || verifyDTO.getVerifyCode() == null
                || verifyDTO.getVerifyCode().trim().isEmpty()) {
            throw new BadRequestException("核销码不能为空");
        }
        if (verifyDTO.getShopId() == null) {
            throw new BadRequestException("门店id不能为空");
        }
        String verifyCode = verifyDTO.getVerifyCode().trim().toUpperCase();

        // 用 list 而非 getOne：命中多条时显式报错并留 ERROR 日志（getOne 抛的 TooManyResultsException 会被统一异常处理器翻译成"服务器内部异常"，看不出原因）
        List<Order> matched = lambdaQuery().eq(Order::getVerifyCode, verifyCode).list();
        if (CollUtils.isEmpty(matched)) {
            throw new BadRequestException("核销码不存在");
        }
        if (matched.size() > 1) {
            log.error("核销码命中多笔订单，拒绝核销: verifyCode={}, orderIds={}",
                    verifyCode, matched.stream().map(Order::getId).collect(Collectors.toList()));
            throw new BizIllegalException("核销码异常（匹配到多笔订单），请联系客服处理");
        }
        Order order = matched.get(0);

        // 券的适用门店与核销门店必须一致，不一致属于越权
        if (!Objects.equals(order.getShopId(), verifyDTO.getShopId())) {
            log.warn("核销码不属于该门店: orderId={}, orderShopId={}, verifyShopId={}, operator={}",
                    order.getId(), order.getShopId(), verifyDTO.getShopId(), operator);
            throw new ForbiddenException("核销码不属于该门店");
        }

        // 授权判定必须早于业务状态判定：否则无权限者能通过错误文案探测"这个码是否已被使用/是否付款"。
        // 门店取自订单（权威值），不取请求参数 —— 请求里的 shopId 此时已校验一致，但权限只认订单侧的门店。
        assertStaffOfOrderShop(order, operator);

        if (order.getStatus() == null || order.getStatus() != Order.STATUS_PAID) {
            throw new BizIllegalException("核销码已使用或订单状态不允许核销");
        }

        // 受影响行数是唯一的成功判据，并发/重复调用天然幂等
        boolean verified = lambdaUpdate()
                .eq(Order::getId, order.getId())
                .eq(Order::getStatus, Order.STATUS_PAID)
                .set(Order::getStatus, Order.STATUS_VERIFIED)
                .set(Order::getVerifyTime, LocalDateTime.now())
                .update();
        if (!verified) {
            log.info("核销未生效（状态已被并发修改）: orderId={}, verifyCode={}", order.getId(), verifyCode);
            throw new BizIllegalException("核销码已使用或订单状态不允许核销");
        }
        log.info("订单核销成功: orderId={}, shopId={}, userId={}, operator={}",
                order.getId(), order.getShopId(), order.getUserId(), operator);

        // 核销事件（为门店结算/对账留钩子）投递失败只记日志：核销事实已落库，消息丢失只是"结算晚一点"，
        // 为发消息把核销回滚却会变成"顾客到店用不了券"，故可用性优先
        try {
            OrderVerifiedEvent event = new OrderVerifiedEvent()
                    .setBizId(String.valueOf(order.getId()))
                    .setOrderId(order.getId())
                    .setShopId(order.getShopId())
                    .setUserId(order.getUserId())
                    .setTotalFee(order.getTotalFee())
                    .setOccurredAt(System.currentTimeMillis());
            rabbitTemplate.convertAndSend(MqConstants.TRADE_EXCHANGE, MqConstants.ORDER_VERIFIED_KEY, event);
        } catch (Exception e) {
            log.error("核销事件投递失败（不影响核销结果，核销已落库）: orderId={}", order.getId(), e);
        }
        return order.getId();
    }

    /**
     * 核销前置的授权断言：调用者必须是该订单所属门店的在岗员工。
     *
     * 【为什么在交易域做、数据在门店域】员工关系属于门店域（shop_staff 表），交易域只持有订单；
     * 由交易域在入口处强制、向门店域查询（Feign 内部接口），既保证"所有核销路径都过同一道闸"，
     * 又不需要在交易库复制一份员工表（复制必然漂移：离职了还能核销）。
     *
     * 【fail-closed 的两条断言路径】
     * 1) 查询返回 false / null → 拒绝（非员工）；
     * 2) 门店服务不可用或降级方法抛异常（Feign 超时、Sentinel 包装成 AssertionError 等）→ 拒绝并打 ERROR，
     *    让"核销不可用"在日志里可归因。
     * 绝不允许"查不到就放行"：修复前的漏洞本质就是把"没校验"当成"校验通过"。
     *
     * 【注意】这里抛的是 ForbiddenException（HTTP 403）：语义是"你没有这个权限"，而不是"参数错了"。
     * 前端据此展示明确文案；监控上 403 属预期内的业务拒绝，不会污染 5xx 告警。
     */
    private void assertStaffOfOrderShop(Order order, Long operator) {
        boolean staff;
        try {
            staff = Boolean.TRUE.equals(shopClient.isStaff(order.getShopId(), operator));
        } catch (RuntimeException | AssertionError e) {
            // 【为什么连 AssertionError 一起接】Feign 的 fallbackFactory 由 Sentinel 的
            // SentinelInvocationHandler 调用，它把降级方法抛出的异常包成 AssertionError
            // （不是 Exception，catch(Exception) 接不到，会一路冒到全局兜底变成 400/500，
            // 把"门店服务不可用"说成"请求参数有误"）。实测停掉 shop 服务时命中的就是这条。
            // 降级类抛出的 BizIllegalException 也走这里：门店服务不可用时核销必须不可用，不能 fail-open。
            log.error("核销鉴权失败：门店员工校验不可用，按拒绝处理: orderId={}, shopId={}, operator={}",
                    order.getId(), order.getShopId(), operator, e);
            throw new ForbiddenException("暂时无法核销：门店员工校验不可用，请稍后重试");
        }
        if (!staff) {
            log.warn("核销越权拦截：调用者不是该门店员工: orderId={}, shopId={}, operator={}",
                    order.getId(), order.getShopId(), operator);
            throw new ForbiddenException("仅门店员工可核销");
        }
    }

    /** 先持久化 2→9 拒绝核销；余额、库存、券和支付账本全部确认后再 9→5。 */
    @Override
    public void refundMyOrder(Long orderId) {
        Order order = getById(orderId);
        if (order == null) throw new BadRequestException("订单不存在");
        if (!Objects.equals(requireLogin(), order.getUserId())) throw new ForbiddenException("无权操作他人订单");
        if (Integer.valueOf(Order.STATUS_REFUNDING).equals(order.getStatus())) {
            recoverCompensation(orderId);
            return;
        }
        assertNotVerified(order, "退款");
        if (!localTransition(orderId, Order.STATUS_PAID, Order.STATUS_REFUNDING)) {
            throw new BizIllegalException("订单状态不允许退款");
        }
        try { recoverCompensation(orderId); }
        // Sentinel can wrap a throwing Feign fallback in AssertionError.
        catch (RuntimeException | AssertionError e) { log.error("退款已受理，补偿任务将继续恢复: orderId={}", orderId, e); }
    }

    @Override
    public void recoverCompensation(Long orderId) {
        // afterCommit callbacks still have the outer SqlSession bound. Read in a fresh transaction
        // so its first-level cache cannot hide the committed 8/9 transition.
        TransactionTemplate read = new TransactionTemplate(transactionTemplate.getTransactionManager());
        read.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Order order = read.execute(status -> getById(orderId));
        if (order == null) return;
        boolean refund = Integer.valueOf(Order.STATUS_REFUNDING).equals(order.getStatus());
        if (!refund && !Integer.valueOf(Order.STATUS_CANCELING).equals(order.getStatus())) return;
        Long previous = UserContext.getUser();
        try {
            UserContext.setUser(order.getUserId());
            if (refund) userClient.refundMoney(order.getUserId(), order.getTotalFee(), "ORDER_REFUND:" + orderId);
            recoverStock(order, refund ? MqConstants.BIZ_ORDER_REFUNDED : MqConstants.BIZ_ORDER_CANCELED);
            if (order.getVoucherId() != null && !Boolean.TRUE.equals(voucherClient.refundMyVoucher(order.getVoucherId(), orderId))) {
                throw new BizIllegalException("退券未确认，等待重试");
            }
            if (refund) payClient.closePayOrder(orderId);
            localTransition(orderId, refund ? Order.STATUS_REFUNDING : Order.STATUS_CANCELING, Order.STATUS_CANCELED);
        } finally {
            if (previous == null) UserContext.removeUser(); else UserContext.setUser(previous);
        }
    }

    private UserVoucherDTO queryUsableVoucher(Long userVoucherId, Long shopId) {
        UserVoucherDTO voucher;
        try {
            voucher = voucherClient.queryMyVoucher(userVoucherId);
        } catch (Exception e) {
            log.warn("查询优惠券失败: userVoucherId={}, userId={}", userVoucherId, UserContext.getUser(), e);
            throw new BadRequestException("优惠券不可用：券不存在或不属于当前用户");
        }
        if (voucher == null || voucher.getId() == null) {
            throw new BadRequestException("优惠券不可用：券不存在或不属于当前用户");
        }
        if (voucher.getStatus() == null || voucher.getStatus() != 1) {
            throw new BadRequestException("优惠券不可用：" + (voucher.getStatus() != null && voucher.getStatus() == 2
                    ? "该券已被使用" : "该券已过期"));
        }
        if (voucher.getExpireTime() != null && voucher.getExpireTime().isBefore(LocalDateTime.now())) {
            // status 仍为 1 但已过期：营销域按 expire_time 惰性判定过期，交易域必须再判一次（两边时钟可能不同，券也可能刚在这一步之间过期）
            throw new BadRequestException("优惠券不可用：该券已过期");
        }
        // 券的 shopId 为空 = 全平台通用券；非空则必须与订单门店一致，否则会出现"用 A 店的券抵 B 店的单"
        if (voucher.getShopId() != null && !Objects.equals(voucher.getShopId(), shopId)) {
            throw new BadRequestException("优惠券不可用：该券仅限门店 " + voucher.getShopId() + " 使用");
        }
        if (voucher.getActualValue() == null || voucher.getActualValue() <= 0) {
            throw new BadRequestException("优惠券不可用：券面额异常");
        }
        return voucher;
    }

    /**
     * 锁定券：把券置为已使用并绑定订单号（营销域条件更新 1 → 2），约定在本地事务提交之后执行。
     * 顺序原因：营销域会拿 orderId 回查交易订单（存在、属于本人、门店一致、voucher_id 匹配），事务内订单行还未提交、
     * 另一个连接看不到它，回查会得到"订单不存在"而拒锁 —— 券是用户资产，只有确认订单真正落地后才允许锁死。
     * 锁券失败时事务已提交、无法靠回滚撤销，只能显式补偿：关单 + 回滚库存（用户还没付过钱，取消不产生资金动作）。
     */
    private void lockVoucherAfterCommit(Long userVoucherId, Long orderId) {
        Runnable lockAction = () -> {
            try {
                Boolean locked = voucherClient.useMyVoucher(userVoucherId, orderId);
                if (locked == null || !locked) {
                    throw new BizIllegalException("优惠券锁定失败");
                }
                log.info("优惠券已锁定到订单: userVoucherId={}, orderId={}", userVoucherId, orderId);
            } catch (Exception e) {
                log.error("锁券失败，补偿关闭订单并回滚库存: userVoucherId={}, orderId={}", userVoucherId, orderId, e);
                compensateVoucherLockFailure(orderId);
                throw new BadRequestException("优惠券不可用：该券刚被其它订单占用，请重新下单");
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    lockAction.run();
                }
            });
        } else {
            // 理论不会发生（本方法由 @Transactional 方法调用）；真要走到这里也必须锁，否则就是"订单用了券但券没锁"的一券多用资损
            lockAction.run();
        }
    }

    /** 锁券失败后的补偿：关闭订单 + 回滚库存。只处理"仍是待支付"的订单；若用户已经付款，绝不能在这里关单，只记 ERROR 交人工/对账 */
    private void compensateVoucherLockFailure(Long orderId) {
        try { cancelOrder(orderId); }
        catch (RuntimeException e) { log.error("锁券失败后的关单暂未完成，超时扫描将重试: orderId={}", orderId, e); }
    }

    /** 生成核销码（查重命中则重试）：verify_code 上没有唯一索引，唯一性的最终防线在核销侧 —— 同码命中多条即拒绝核销并告警 */
    private String generateVerifyCode() {
        for (int i = 0; i < 5; i++) {
            String code = randomVerifyCode();
            // 【注意】MyBatis-Plus 3.5.x 起 selectCount 返回 Long，接收类型不能写成 Integer
            Long exists = baseMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .eq(Order::getVerifyCode, code));
            if (exists == null || exists.longValue() == 0L) {
                return code;
            }
            log.warn("核销码重复，重新生成: code={}, 第 {} 次", code, i + 1);
        }
        // 连续撞码 5 次说明码空间被大量占用或数据异常，此时不能"随便给一个"：生成失败只是这次重试，撞码会让两笔订单无法区分
        throw new BizIllegalException("核销码生成失败，请重试");
    }

    private String randomVerifyCode() {
        char[] chars = new char[VERIFY_CODE_LENGTH];
        for (int i = 0; i < VERIFY_CODE_LENGTH; i++) {
            chars[i] = VERIFY_CODE_ALPHABET[VERIFY_CODE_RANDOM.nextInt(VERIFY_CODE_ALPHABET.length)];
        }
        return new String(chars);
    }


    private Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        return userId;
    }

    private OrderVO toOrderVO(Order order, List<OrderDetail> details) {
        OrderVO vo = BeanUtils.copyBean(order, OrderVO.class);
        if (!CollUtils.isEmpty(details)) {
            vo.setDetails(details.stream()
                    .map(d -> BeanUtils.copyBean(d, OrderDetailVO.class))
                    .collect(Collectors.toList()));
        }
        // 核销码只对订单归属人可见：它是到店出示即可核销的可变现凭据，故在出口处再兜一次，非本人即置空
        Long currentUserId = UserContext.getUser();
        if (vo.getVerifyCode() != null
                && (currentUserId == null || !currentUserId.equals(order.getUserId()))) {
            vo.setVerifyCode(null);
        }
        return vo;
    }
}
