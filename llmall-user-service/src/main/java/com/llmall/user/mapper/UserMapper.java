package com.llmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.llmall.user.domain.po.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户账号表数据访问：口令列读取、余额扣减与退款入账。
 */
public interface UserMapper extends BaseMapper<User> {
    /**
     * 查询支付密码哈希。
     *
     * 【注意】支付校验必须只读 pay_password 列，禁止改读 password：
     * 两列混用会让"改登录密码"顺带改掉支付密码的能力。单独取一列也避免了把整行 User 带进支付链路。
     */
    @Select("select pay_password from user where id = #{userId}")
    String selectPayPassword(@Param("userId") Long userId);

    /**
     * 扣减余额。
     *
     * 【注意】条件更新：SQL 自带 balance >= totalFee 前置条件，余额不足时不更新任何行、
     * 返回 0，调用方必须检查返回值，禁止假定扣款成功。
     */
    @Update("update user set balance = balance - #{totalFee} where id = #{userId} and balance >= #{totalFee}")
    int updateMoney(@Param("userId") Long userId, @Param("totalFee") Integer totalFee);

    /**
     * 退款入账：把金额加回余额，返回受影响行数。
     *
     * 用数据库端原子自增（balance = balance + 入参），而非"查出来在内存里加再写回"：
     * 并发退款下读改写会互相覆盖丢钱。本方法无金额上限判断，余额只会因此变大。
     */
    @Update("update user set balance = balance + #{amount} where id = #{userId}")
    int refundMoney(@Param("userId") Long userId, @Param("amount") Integer amount);
}
