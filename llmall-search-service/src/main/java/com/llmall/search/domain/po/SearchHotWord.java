package com.llmall.search.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 搜索热词统计（按天）。
 * 运行时热词计数放在 Redis ZSet（ZINCRBY 原子累加、随时取 Top N），
 * 由定时任务定期落库，避免高频写 MySQL。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("search_hot_word")
public class SearchHotWord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String word;

    private Long searchCount;

    private LocalDate statDate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
