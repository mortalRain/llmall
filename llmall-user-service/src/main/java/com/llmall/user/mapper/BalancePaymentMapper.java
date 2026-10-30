package com.llmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.user.domain.po.BalancePayment;
import org.apache.ibatis.annotations.*;

public interface BalancePaymentMapper extends BaseMapper<BalancePayment> {
    @Insert("INSERT INTO balance_payment(payment_key,user_id,amount,state) VALUES(#{key},#{userId},#{amount},'NEW') "
            + "ON DUPLICATE KEY UPDATE payment_key=payment_key")
    void ensure(@Param("key") String key, @Param("userId") Long userId, @Param("amount") Integer amount);

    @Select("SELECT payment_key,user_id,amount,state FROM balance_payment WHERE payment_key=#{key} FOR UPDATE")
    BalancePayment lock(@Param("key") String key);

    @Update("UPDATE balance_payment SET state=#{state},update_time=NOW() WHERE payment_key=#{key}")
    int transition(@Param("key") String key, @Param("state") String state);
}
