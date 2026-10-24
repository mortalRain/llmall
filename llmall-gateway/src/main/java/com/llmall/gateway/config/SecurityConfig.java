package com.llmall.gateway.config;
import com.llmall.common.security.InternalCredentialProperties;
import com.llmall.common.security.JwtProperties;
import com.llmall.common.security.JwtTool;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.rsa.crypto.KeyStoreKeyFactory;
import java.security.KeyPair;

/**
 * 网关的认证相关 Bean 装配：口令编码器、JWT 密钥对与 JwtTool。
 *
 * 【设计意图】JwtTool 的实现放在 llmall-common（网关与用户服务必须共用一份，避免签发/验签漂移），
 * common 不在组件扫描范围内，因此在这里以 Bean 显式暴露。
 *
 * 【注意】密钥对从 JwtProperties 指向的 JKS 中读取，properties.getPassword() 同时作为密钥库口令与私钥口令传入，
 * 两者在本实现中是同一个配置项，不能只改其中一个。网关与用户服务必须读到同一份密钥库：
 * 一边换库、换 alias 或改口令而另一边没同步，表现为"登录成功但所有接口 401"（验签失败），且只在跨服务调用时暴露。
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, InternalCredentialProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    @Bean
    public KeyPair keyPair(JwtProperties properties){
        KeyStoreKeyFactory keyStoreKeyFactory =
                new KeyStoreKeyFactory(
                        properties.getLocation(),
                        properties.getPassword().toCharArray());
        return keyStoreKeyFactory.getKeyPair(
                properties.getAlias(),
                properties.getPassword().toCharArray());
    }

    /** JwtTool 由 llmall-common 提供，在本类以 Bean 暴露的原因见类注释 */
    @Bean
    public JwtTool jwtTool(KeyPair keyPair) {
        return new JwtTool(keyPair);
    }
}
