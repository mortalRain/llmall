package com.llmall.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.promotion.domain.po.ConsumeRecord;

/**
 * 消息消费幂等记录 Mapper。
 * 幂等不靠"先查再插"（同样有并发窗口），而是直接 insert 并捕获唯一键冲突，交给 uk_consume 裁决。
 */
public interface ConsumeRecordMapper extends BaseMapper<ConsumeRecord> {
}
