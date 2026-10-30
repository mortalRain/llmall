package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.Voucher;

/** 券模板查询与普通领取创建阶段的短事务行锁。 */
public interface VoucherMapper extends BaseMapper<Voucher> {
    /** Serializes short claim transactions; never held during a funds RPC. */
    @org.apache.ibatis.annotations.Select("SELECT * FROM voucher WHERE id=#{id} FOR UPDATE")
    Voucher lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
