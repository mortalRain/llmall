package com.llmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.llmall.user.domain.po.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface UserMapper extends BaseMapper<User> {
    /**
     * 扣减余额。
     *
     * 【修复】原 SQL 是 `set balance = balance - ${totalFee} where id = #{userId}`：
     *  ① 没有余额充足性判断 —— 余额不足时会把余额扣成负数（资损）；
     *  ② 方法返回 void，调用方连"到底扣没扣成功"都感知不到；
     *  ③ 用 `${}` 直接拼接金额，存在注入面。
     * 现在改为条件更新 + 返回受影响行数 + `#{}` 预编译占位符。
     */
    @Update("update user set balance = balance - #{totalFee} where id = #{userId} and balance >= #{totalFee}")
    int updateMoney(@Param("userId") Long userId, @Param("totalFee") Integer totalFee);

    /**
     * 退款入账：把金额加回余额，返回受影响行数。
     *
     * 为什么用 `balance = balance + #{amount}` 这种**数据库端自增**而不是
     * "先 select 出余额、在内存里加、再 update"：并发退款（或多条退款路径同时命中同一用户）下，
     * 读改写会互相覆盖，最后一笔写入会抹掉前一笔的钱；而 `balance = balance + ?` 是在
     * 行锁内完成的原子累加，天然不会丢更新 —— 这和扣款用 `balance >= amount` 做条件更新
     * 是同一个思路：把"钱的运算"交给数据库，而不是应用层。
     * 不用加减金额判断余额上限：余额只可能因为本方法变大，没有"加多了要把人加破产"的风险。
     */
    @Update("update user set balance = balance + #{amount} where id = #{userId}")
    int refundMoney(@Param("userId") Long userId, @Param("amount") Integer amount);
}
