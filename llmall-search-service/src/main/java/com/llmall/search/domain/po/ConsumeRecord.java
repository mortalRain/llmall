package com.llmall.search.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息消费记录（搜索域幂等闸门 + 门店域对账的判据）。
 *
 * 【注意】对账判据是"有行即已处理"：消费时先按唯一键 uk_consume 插占位、再更新读模型，
 * 两者必须同事务提交 —— 提交成功则有行，门店域问过来即可答"已处理"；
 * 抛异常回滚则占位一并消失，门店域会得到"明确未处理"并补发，
 * 正好覆盖"消费到一半宕机 / ack 丢失"这类发送方看不见的缺口。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("consume_record")
public class ConsumeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String bizType;

    private String bizId;

    /** 消费方标识（服务名），同一事件可能被多个服务各自消费 */
    private String consumer;

    private LocalDateTime createTime;
}
