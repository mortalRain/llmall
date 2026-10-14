package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.ConsumeRecord;

/**
 * 消息消费幂等记录 Mapper：直接 insert 并捕获唯一键冲突，由 uk_consume 唯一键裁决，不做"先查再插"。
 */
public interface ConsumeRecordMapper extends BaseMapper<ConsumeRecord> {
}
