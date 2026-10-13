package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.MessageLog;

/**
 * 本地消息表 Mapper（内容域）。
 *
 * 无自定义 SQL：登记/更新状态/按 (status, next_retry_time) 捞待重试行都由 MyBatis-Plus 的
 * BaseMapper + LambdaWrapper 完成（与门店域、交易域同名 Mapper 一致）。
 * 本接口所在包 com.llmall.content.mapper 已在 ContentApplication 的 @MapperScan 里，
 * 不需要额外的扫描配置。
 */
public interface MessageLogMapper extends BaseMapper<MessageLog> {
}
