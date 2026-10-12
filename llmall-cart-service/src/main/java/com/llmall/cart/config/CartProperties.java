package com.llmall.cart.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llmall.cart")
public class CartProperties {
    /**
     * 购物车最大条目数。**给默认值**是有意的：该值来自 Nacos 的 llmall-cart-service.yaml，
     * 配置中心缺这一项时字段会是 null，而调用处是 `count >= getMaxAmount()` 这种拆箱比较，
     * 会直接抛 NPE 变成 500（实测踩过）。默认 10 与配置一致，缺配置时退化为"仍可用"。
     */
    private Integer maxAmount = 10;
}
