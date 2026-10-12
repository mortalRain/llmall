package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.UserVoucher;

/**
 * 用户券实例 Mapper。
 *
 * 注意：本服务**没有**为"我的券"写自定义分页 SQL，也没有提供"先查再插"的领取方法——
 * 领取/核销/退回全部通过条件 UPDATE 与唯一键冲突来保证并发正确性，见 VoucherServiceImpl。
 */
public interface UserVoucherMapper extends BaseMapper<UserVoucher> {
}
