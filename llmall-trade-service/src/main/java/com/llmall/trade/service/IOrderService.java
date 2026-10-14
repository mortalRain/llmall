package com.llmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.common.domain.PageDTO;
import com.llmall.common.domain.PageQuery;
import com.llmall.trade.domain.dto.OrderFormDTO;
import com.llmall.trade.domain.dto.OrderVerifyDTO;
import com.llmall.trade.domain.po.Order;
import com.llmall.trade.domain.vo.OrderVO;


/**
 * 订单交易服务：下单、标记支付成功、关单、到店核销与退款。
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
     * 【注意】订单终态 status=7 只由本方法写入，是唯一的入口。
     *
     * @param verifyDTO 核销码 + 核销门店 id
     * @return 被核销的订单 id
     */
    Long verifyOrder(OrderVerifyDTO verifyDTO);

    /**
     * 已支付订单退款（用户发起）。
     *
     * 与 cancelOrder 的边界：取消只处理"未付款"的单子（钱没动过，回滚库存即可），
     * 退款处理"钱已经付了、但还没到店核销"的单子，要退余额、回滚库存、退回优惠券、
     * 关闭支付单，四件事都要落地，缺一件就是一笔对不上的账。
     */
    void refundMyOrder(Long orderId);
}
