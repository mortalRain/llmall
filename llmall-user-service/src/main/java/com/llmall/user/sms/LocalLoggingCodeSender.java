package com.llmall.user.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本地联调用的验证码通道：把验证码写进日志，便于不接短信通道时完成登录与改密。
 *
 * 【注意】只在 local profile 下装配。生产 profile 下本类不是 Bean，日志里不会出现验证码；
 * 这条边界由 Spring 的 profile 装配保证，不依赖任何布尔开关。
 */
@Slf4j
@Component
@Profile("local")
public class LocalLoggingCodeSender implements CodeSender {

    @Override
    public void send(String phone, String scene, String code) {
        log.info("【验证码-仅本地】scene={}, phone={}, code={}", scene, mask(phone), code);
    }

    private String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
