package com.llmall.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.user.domain.po.BalanceFlow;

/**
 * 余额流水 Mapper。
 *
 * <p>不需要自定义 SQL：幂等完全依赖表的唯一键 {@code uk_biz(biz_type, biz_no)} ——
 * 重复插入时 MyBatis-Plus 的 {@code insert} 会抛 {@code DuplicateKeyException}，
 * 调用方据此判定"这笔业务号已经处理过"。把幂等交给数据库唯一键，而不是"先查后插"
 * （并发下先查后插必然漏，两个请求都会查到"不存在"）。
 */
public interface BalanceFlowMapper extends BaseMapper<BalanceFlow> {
}
