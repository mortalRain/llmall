package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.UserVoucher;

/**
 * 用户券实例 Mapper。
 *
 * 【注意】领取/核销/退回均通过条件 UPDATE 与唯一键冲突保证并发正确性，没有"先查再插"的方法。
 */
public interface UserVoucherMapper extends BaseMapper<UserVoucher> {
}
