package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.SeckillVoucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 条件扣减库存（乐观锁的"无版本号"写法）：判断写进 WHERE、扣减写进 SET，由 InnoDB 行锁保证原子。
     *
     * 【注意】不能"先 select 出 stock，再 update set stock = 查到的值 - 1"：并发下会丢更新导致超卖；
     * 也不能用 MP 的 updateById，它按实体字段拼 SET，表达不了 stock = stock - 1 这种自增语义。
     *
     * @return 受影响行数，返回 0 即库存已空，业务侧据此回滚
     */
    @Update("UPDATE seckill_voucher SET stock = stock - 1, update_time = NOW() "
            + "WHERE voucher_id = #{voucherId} AND stock > 0")
    int deductStock(@Param("voucherId") Long voucherId);
}
