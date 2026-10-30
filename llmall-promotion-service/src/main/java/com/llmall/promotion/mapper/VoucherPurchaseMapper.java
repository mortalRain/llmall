package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.VoucherPurchase;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface VoucherPurchaseMapper extends BaseMapper<VoucherPurchase> {
    @Select("SELECT * FROM voucher_purchase WHERE user_id=#{userId} AND request_id=#{requestId}")
    VoucherPurchase find(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Select("SELECT * FROM voucher_purchase WHERE id=#{id} FOR UPDATE")
    VoucherPurchase lock(@Param("id") Long id);

    @Select("SELECT * FROM voucher_purchase WHERE user_id=#{userId} AND voucher_id=#{voucherId} "
            + "AND state IN ('ACTIVE','CANCELING') LIMIT 1")
    VoucherPurchase findActive(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("SELECT * FROM voucher_purchase WHERE next_check_time<=#{now} AND "
            + "(state='CANCELING' OR (state='ACTIVE' AND create_time<=#{deadline})) "
            + "ORDER BY next_check_time,id LIMIT 100")
    List<VoucherPurchase> recoverable(@Param("now") LocalDateTime now, @Param("deadline") LocalDateTime deadline);

    @Update("UPDATE voucher_purchase SET next_check_time=#{next} WHERE id=#{id} AND state IN ('ACTIVE','CANCELING')")
    void defer(@Param("id") Long id, @Param("next") LocalDateTime next);
}
