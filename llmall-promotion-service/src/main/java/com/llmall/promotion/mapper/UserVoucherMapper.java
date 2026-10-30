package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.UserVoucher;

/**
 * 用户券实例 Mapper。
 *
 * 【注意】领取/核销/退回均通过条件 UPDATE 与唯一键冲突保证并发正确性，没有"先查再插"的方法。
 */
public interface UserVoucherMapper extends BaseMapper<UserVoucher> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM user_voucher WHERE id=#{id} FOR UPDATE")
    UserVoucher lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM voucher_order_cancel WHERE user_voucher_id=#{id} AND order_id=#{orderId}")
    int canceled(@org.apache.ibatis.annotations.Param("id") Long id, @org.apache.ibatis.annotations.Param("orderId") Long orderId);

    @org.apache.ibatis.annotations.Insert("INSERT INTO voucher_order_cancel(user_voucher_id,order_id) VALUES(#{id},#{orderId}) "
            + "ON DUPLICATE KEY UPDATE order_id=order_id")
    void cancelBinding(@org.apache.ibatis.annotations.Param("id") Long id, @org.apache.ibatis.annotations.Param("orderId") Long orderId);
}
