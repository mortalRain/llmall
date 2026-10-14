package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果（跨服务传输用）。
 *
 * 【注意】不能复用 llmall-common 的 PageDTO：它引用了 MyBatis-Plus 的 Page 类型，
 * 会让纯契约模块带上 ORM 依赖；字段名与 PageDTO 保持一致，JSON 可直接反序列化。
 */
@Data
@Schema(description = "分页结果")
public class ShopPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "总条数")
    private Long total;

    @Schema(description = "总页数")
    private Long pages;

    @Schema(description = "当前页数据")
    private List<ShopDTO> list;
}
