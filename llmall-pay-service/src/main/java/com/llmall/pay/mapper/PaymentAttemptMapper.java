package com.llmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.pay.domain.po.PaymentAttempt;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface PaymentAttemptMapper extends BaseMapper<PaymentAttempt> {
    @Select("SELECT * FROM payment_attempt WHERE next_check_time<=NOW() AND "
            + "(state='CANCELING' OR (state='ACTIVE' AND create_time<DATE_SUB(NOW(),INTERVAL 2 MINUTE)) "
            + "OR (state='SUCCEEDED' AND notified=0)) ORDER BY next_check_time,id LIMIT 100")
    List<PaymentAttempt> recoverable();

    @Update("UPDATE payment_attempt SET next_check_time=DATE_ADD(NOW(),INTERVAL 30 SECOND) WHERE id=#{id}")
    void defer(@Param("id") Long id);

    @Update("UPDATE payment_attempt SET notified=1 WHERE id=#{id} AND state='SUCCEEDED'")
    void notified(@Param("id") Long id);
}
