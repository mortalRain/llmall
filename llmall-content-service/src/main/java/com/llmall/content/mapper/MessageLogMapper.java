package com.llmall.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.content.domain.po.MessageLog;

/**
 * 本地消息表 Mapper（内容域）。
 *
 * 无自定义 SQL：登记、更新状态、按 (status, next_retry_time) 捞待重试行都由 BaseMapper + LambdaWrapper 完成。
 */
public interface MessageLogMapper extends BaseMapper<MessageLog> {
}
