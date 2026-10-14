package com.llmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.user.domain.po.BalanceFlow;

/**
 * 余额流水 Mapper。
 *
 * 【注意】幂等完全依赖 {@code uk_biz(biz_type, biz_no)} 唯一键：重复插入由数据库抛
 * {@code DuplicateKeyException}，调用方据此判定业务号已处理；禁止改成"先查后插"（并发下必漏）。
 */
public interface BalanceFlowMapper extends BaseMapper<BalanceFlow> {
}
