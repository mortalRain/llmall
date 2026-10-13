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
 * 表本身早已存在（sql/merged/02-shared-infra.sql 给每个消费方库都建了 consume_record），
 * 但搜索域之前**没有用它**：ShopHotChangedListener 是直接调 service 更新读模型的。
 * 审计 B4 要求"对账兜底：问搜索侧这条事件你处理了吗"——
 * 而"处理了没有"必须有一个**和业务更新同事务**写下的判据，否则对账只能靠猜。
 * 于是这里补上 PO/Mapper，消费时先插占位（唯一键 uk_consume 裁决），再更新读模型，
 * 两者同事务提交：
 *  - 提交成功 → 占位在 ⇒ 门店域问过来时能回答"已处理"；
 *  - 抛异常回滚 → 占位一并消失 ⇒ 门店域会得到"明确未处理"并补发，
 *    正好覆盖"消费到一半宕机 / ack 丢失"这类发送方看不见的缺口。
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
