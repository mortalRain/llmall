package com.llmall.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.llmall.trade.domain.po.OrderLogistics;
import com.llmall.trade.mapper.OrderLogisticsMapper;
import com.llmall.trade.service.IOrderLogisticsService;
import org.springframework.stereotype.Service;

/**
 * 订单物流服务实现：无自定义方法，基础 CRUD 由 MyBatis-Plus ServiceImpl 提供。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Service
public class OrderLogisticsServiceImpl extends ServiceImpl<OrderLogisticsMapper, OrderLogistics> implements IOrderLogisticsService {

}
