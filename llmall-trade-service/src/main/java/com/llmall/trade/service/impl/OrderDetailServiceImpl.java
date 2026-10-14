package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.llmall.trade.domain.po.OrderDetail;
import com.llmall.trade.mapper.OrderDetailMapper;
import com.llmall.trade.service.IOrderDetailService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单明细查询：按订单 id 取该订单下的全部商品明细。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Service
public class OrderDetailServiceImpl extends ServiceImpl<OrderDetailMapper, OrderDetail> implements IOrderDetailService {


    @Override
    public List<OrderDetail> listByOrderId(Long orderId) {
        LambdaQueryWrapper<OrderDetail> queryWrapper = new LambdaQueryWrapper<OrderDetail>()
                .eq(OrderDetail::getOrderId, orderId);
        return this.list(queryWrapper);
    }
}
