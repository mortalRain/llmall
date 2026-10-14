package com.llmall.cart.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llmall.cart")
public class CartProperties {
    /** 购物车最大条目数；默认值不可去：配置中心缺该项时字段为 null，调用处拆箱比较会直接 NPE */
    private Integer maxAmount = 10;
}
