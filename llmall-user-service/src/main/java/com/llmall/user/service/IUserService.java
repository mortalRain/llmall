package com.llmall.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.po.User;
import com.llmall.user.domain.vo.UserLoginVO;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface IUserService extends IService<User> {

    /** 用户名 + 密码登录（商城体系） */
    UserLoginVO login(LoginFormDTO loginFormDTO);

    /** 发送手机验证码（合并自点评体系）：带 60 秒频控，验证码 5 分钟有效 */
    void sendCode(String phone);

    /** 手机验证码登录：未注册手机号自动注册 */
    UserLoginVO loginByCode(SmsLoginDTO smsLoginDTO);

    /** 获取当前登录用户（身份来自网关注入的 user-info 头，经拦截器放入 UserContext） */
    UserLoginVO currentUser();

    void deductMoney(String pw, Integer totalFee);

    /**
     * 退款入账（内部接口，供交易域的退款闭环调用）：把 amount 分加回 userId 的余额。
     *
     * 只做"加钱"这一件事，不在这里判断"该不该退"：退款是否成立属于交易域的业务判断
     * （订单归属、订单状态、是否已退），用户域只回答"钱有没有到账"。
     * 职责分开的好处是：交易域可以在余额退回失败时按自己的事务语义做补偿（把订单改回已支付），
     * 而不是把订单状态的知识塞进用户域。
     */
    void refundMoney(Long userId, Integer amount);
}
