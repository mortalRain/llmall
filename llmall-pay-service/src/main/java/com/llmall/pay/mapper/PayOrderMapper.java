package com.llmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.pay.domain.po.PayOrder;


/**
 * 支付单表的 MyBatis-Plus 基础 Mapper：没有自定义 SQL，查询与条件更新全部由 ServiceImpl 的
 * lambdaQuery / lambdaUpdate 条件构造器生成（状态推进依赖 where 子句承担不变量，见 PayOrderServiceImpl）。
 *
 * @author 同城生活
 * @since 2023-05-16
 */
public interface PayOrderMapper extends BaseMapper<PayOrder> {

}
