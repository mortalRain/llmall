package com.llmall.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果（跨服务传输用）。
 *
 * 为什么不用 llmall-common 的 PageDTO：PageDTO 引用了 MyBatis-Plus 的 Page 类型，
 * 而 llmall-api 作为"纯契约模块"可以不存在 ORM 依赖（否则任何引用它的服务都被迫引入 MP）。
 * 契约模块的依赖要尽可能小，这是微服务里"共享库越小越安全"的实践。
 * 字段名与 PageDTO 保持一致，因此 JSON 可以直接反序列化。
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
