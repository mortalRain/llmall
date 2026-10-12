package com.llmall.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.trade.domain.po.OrderDetail;

import java.util.List;


/**
 * <p>
 * 订单详情表 服务类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface IOrderDetailService extends IService<OrderDetail> {

    List<OrderDetail> listByOrderId(Long orderId);
}
