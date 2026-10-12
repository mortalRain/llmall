package com.llmall.search.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热词（词 + 搜索次数）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HotWordVO {
    private String word;
    private Double score;
}
