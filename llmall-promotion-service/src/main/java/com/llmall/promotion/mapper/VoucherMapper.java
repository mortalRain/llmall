package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.Voucher;

/**
 * 券模板 Mapper。
 * 全部查询用 MyBatis-Plus 的 LambdaQuery 拼装，没有自定义 SQL —— 逻辑简单，写 XML 反而增加维护面。
 */
public interface VoucherMapper extends BaseMapper<Voucher> {
}
