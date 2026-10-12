package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.llmall.trade.domain.po.OrderDetail;
import com.llmall.trade.mapper.OrderDetailMapper;
import com.llmall.trade.service.IOrderDetailService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 订单详情表 服务实现类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Service
public class OrderDetailServiceImpl extends ServiceImpl<OrderDetailMapper, OrderDetail> implements IOrderDetailService {


    @Override
    public List<OrderDetail> listByOrderId(Long orderId) {
        // 构建Lambda查询条件：根据order_id字段匹配
        LambdaQueryWrapper<OrderDetail> queryWrapper = new LambdaQueryWrapper<OrderDetail>()
                .eq(OrderDetail::getOrderId, orderId);
        // 调用MyBatis-Plus的list方法查询
        return this.list(queryWrapper);
    }
}
