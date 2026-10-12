package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.SeckillVoucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 条件扣减库存（乐观锁的"无版本号"写法）。
     *
     * 为什么不用"先 select 出 stock，再 update set stock = 查到的值 - 1"：
     * 那样在并发下会丢更新（两个线程读到同一个 120，各写回 119，实际卖了两张只扣一张）→ 超卖。
     * 把判断写进 WHERE（stock > 0）、扣减写进 SET（stock = stock - 1），
     * 由 InnoDB 的行锁保证"判断 + 扣减"是一个原子动作；返回 0 行即代表"库存已空"，业务侧据此回滚。
     *
     * 同理也不能用 MP 的 updateById：那会按实体字段拼 SET，无法表达 stock = stock - 1 这种自增语义。
     */
    @Update("UPDATE seckill_voucher SET stock = stock - 1, update_time = NOW() "
            + "WHERE voucher_id = #{voucherId} AND stock > 0")
    int deductStock(@Param("voucherId") Long voucherId);
}
