package com.llmall.content.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 发布探店笔记/评价表单。
 * score 是"评价 → 门店热度"闭环的输入，必须校验范围，否则热度公式会被脏数据带偏。
 */
@Data
@Schema(description = "发布探店笔记表单")
public class BlogFormDTO {

    @NotNull(message = "门店不能为空")
    @Schema(description = "门店 id", required = true)
    private Long shopId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题过长")
    @Schema(description = "标题", required = true)
    private String title;

    @NotBlank(message = "正文不能为空")
    @Size(max = 2048, message = "正文过长")
    @Schema(description = "正文", required = true)
    private String content;

    @Schema(description = "图片，多张以逗号分隔")
    private String images;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    @Schema(description = "评分 1~5", required = true)
    private Integer score;
}
