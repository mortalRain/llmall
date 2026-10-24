package com.llmall.user.sms;

import com.llmall.common.exception.BizIllegalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境的验证码通道占位实现：未接入短信服务商时直接失败，而不是把验证码写进日志。
 *
 * 【注意】这是刻意的 fail-closed：宁可让验证码登录不可用，也不能让验证码进入日志采集链路。
 * 接入短信服务商时，把本类替换为真实实现即可，调用方无需改动。
 */
@Slf4j
@Component
@Profile("!local")
public class SmsCodeSender implements CodeSender {

    @Override
    public void send(String phone, String scene, String code) {
        log.error("短信通道未接入，验证码无法下发: scene={}, phone={}", scene, mask(phone));
        throw new BizIllegalException("短信通道未接入，请使用密码登录或联系管理员");
    }

    private String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
