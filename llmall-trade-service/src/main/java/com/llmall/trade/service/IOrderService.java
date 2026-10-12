package com.llmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.trade.domain.dto.OrderFormDTO;
import com.llmall.trade.domain.dto.OrderVerifyDTO;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.domain.vo.OrderVO;


/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface IOrderService extends IService<Order> {

    Long createOrder(OrderFormDTO orderFormDTO);

    void markOrderPaySuccess(Long orderId);

    void cancelOrder(Long orderId);

    /** 我的订单分页（可按状态筛选），带商品明细 */
    PageDTO<OrderVO> queryMyOrders(PageQuery query, Integer status);

    /** 订单详情（校验归属），带商品明细 */
    OrderVO queryOrderDetail(Long orderId);

    /** 用户主动取消订单（校验归属后复用关单+回滚库存逻辑） */
    void cancelMyOrder(Long orderId);

    /**
     * 到店核销（门店侧）：把"已付款待使用"（status=2）的订单置为"已核销"（status=7）。
     *
     * 这是订单终态 7 的唯一入口 —— 在此之前，status=7 只能靠手工改库，
     * 意味着"履约完成"这件事在系统里没有任何痕迹，门店结算、评价解锁都无从谈起。
     *
     * @param verifyDTO 核销码 + 核销门店 id
     * @return 被核销的订单 id（前端核销成功后可跳转到订单详情，门店端也可据此展示）
     */
    Long verifyOrder(OrderVerifyDTO verifyDTO);

    /**
     * 已支付订单退款（用户发起）。
     *
     * 与 cancelOrder 的区别：取消只处理"未付款"的单子（回滚库存即可，钱没动过），
     * 退款处理的是"钱已经付了、但还没到店核销"的单子，因此要退余额、回滚库存、
     * 退回优惠券，并把支付单关闭 —— 四件事都要落地，缺一件就是一笔对不上的账。
     */
    void refundMyOrder(Long orderId);
}
