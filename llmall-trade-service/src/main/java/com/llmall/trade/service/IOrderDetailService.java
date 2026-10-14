package com.llmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.trade.domain.po.OrderDetail;

import java.util.List;


/**
 * 订单明细服务接口：按订单 id 查询该订单下的商品明细。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface IOrderDetailService extends IService<OrderDetail> {

    List<OrderDetail> listByOrderId(Long orderId);
}
