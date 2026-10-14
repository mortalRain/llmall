package com.llmall.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmall.api.event.OrderPaidEvent;
import com.llmall.common.constant.MqConstants;
import com.llmall.common.utils.CollUtils;
import com.llmall.user.domain.po.ConsumeRecord;
import com.llmall.user.domain.po.PointsFlow;
import com.llmall.user.domain.po.UserInfo;
import com.llmall.user.mapper.ConsumeRecordMapper;
import com.llmall.user.mapper.PointsFlowMapper;
import com.llmall.user.mapper.UserInfoMapper;
import com.llmall.user.service.IPointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 积分服务：消费 order.paid 事件，按订单金额为用户发放下单积分。
 *
 * 【注意】消息占位、业务、流水三段必须在同一个本地事务内完成，失败整体回滚后由 MQ 重投重试；
 * 若占位与业务不在同一事务，就必须能显式撤销占位，否则消息会永久丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsServiceImpl implements IPointsService {

    /** 积分类型：下单获得 */
    private static final int TYPE_ORDER = 1;
    /** 积分折算：100 分（1 元）= 1 积分 */
    private static final int FEE_PER_POINT = 100;
    private static final String CONSUMER = "llmall-user-service";

    private final PointsFlowMapper pointsFlowMapper;
    private final UserInfoMapper userInfoMapper;
    private final ConsumeRecordMapper consumeRecordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean applyOrderPoints(OrderPaidEvent event) {
        if (event == null || event.getOrderId() == null || event.getUserId() == null) {
            log.warn("收到非法的 order.paid 事件: {}", event);
            return false;
        }
        String bizId = String.valueOf(event.getOrderId());

        // 消息级幂等占位，重复投递由唯一索引挡住
        try {
            consumeRecordMapper.insert(new ConsumeRecord()
                    .setBizType(MqConstants.BIZ_ORDER_PAID)
                    .setBizId(bizId)
                    .setConsumer(CONSUMER));
        } catch (DuplicateKeyException e) {
            log.info("该订单消息已消费过，幂等跳过: orderId={}", bizId);
            return false;
        }

        int points = (event.getTotalFee() == null ? 0 : event.getTotalFee()) / FEE_PER_POINT;
        if (points <= 0) {
            log.info("订单金额不足 1 元，不发放积分: orderId={}, totalFee={}", bizId, event.getTotalFee());
            return false;
        }

        // 业务级幂等闸门：流水唯一键挡住重复发放，流水同时是对账判据
        try {
            pointsFlowMapper.insert(new PointsFlow()
                    .setUserId(event.getUserId())
                    .setPoints(points)
                    .setType(TYPE_ORDER)
                    .setBizType(MqConstants.BIZ_ORDER_PAID)
                    .setBizId(bizId)
                    .setRemark("订单支付获得积分"));
        } catch (DuplicateKeyException e) {
            log.info("该订单积分已发放，幂等跳过: orderId={}", bizId);
            return false;
        }

        // 原子累加积分
        int updated = userInfoMapper.addCredits(event.getUserId(), points);
        if (updated == 0) {
            // 用户资料不存在则补建；并发下可能撞唯一键，撞了就改为直接累加
            try {
                userInfoMapper.insert(new UserInfo()
                        .setUserId(event.getUserId())
                        .setCredits(points)
                        .setLevel(0)
                        .setFans(0)
                        .setFollowee(0)
                        .setGender(0));
            } catch (DuplicateKeyException e) {
                userInfoMapper.addCredits(event.getUserId(), points);
            }
        }
        log.info("积分发放成功: orderId={}, userId={}, points={}", bizId, event.getUserId(), points);
        return true;
    }

    @Override
    public boolean isApplied(Long orderId) {
        if (orderId == null) {
            return true;
        }
        // MyBatis-Plus 3.5.x 起 selectCount 返回 Long，不能用 Integer 接收
        Long count = pointsFlowMapper.selectCount(new LambdaQueryWrapper<PointsFlow>()
                .eq(PointsFlow::getBizType, MqConstants.BIZ_ORDER_PAID)
                .eq(PointsFlow::getBizId, String.valueOf(orderId))
                .eq(PointsFlow::getType, TYPE_ORDER));
        return count != null && count > 0;
    }

    @Override
    public List<PointsFlow> listMyFlows(Long userId, Integer limit) {
        if (userId == null) {
            return CollUtils.emptyList();
        }
        int size = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        return pointsFlowMapper.selectList(new LambdaQueryWrapper<PointsFlow>()
                .eq(PointsFlow::getUserId, userId)
                .orderByDesc(PointsFlow::getId)
                .last("LIMIT " + size));
    }
}
