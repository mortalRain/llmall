package com.llmall.user.sms;

/**
 * 验证码发送通道。
 *
 * 【为什么抽接口】发送动作有两套实现，且必须由运行环境决定用哪一套：
 * 本地联调需要看到验证码，生产绝不能把验证码写进日志。用配置开关控制打印很难保证不被误开，
 * 用 Bean 装配则由 profile 决定，生产环境里根本不存在打印实现。
 */
public interface CodeSender {

    /**
     * 发送验证码。
     *
     * @param phone 手机号（已通过格式校验）
     * @param scene 验证码场景，取值见 VerificationCodePolicy 的 SCENE_* 常量
     * @param code  六位数字验证码
     */
    void send(String phone, String scene, String code);
}
