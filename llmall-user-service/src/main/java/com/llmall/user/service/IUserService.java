package com.llmall.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.po.User;
import com.llmall.user.domain.vo.UserLoginVO;

/**
 * 用户账号与余额服务：两种登录方式（用户名密码 / 手机验证码）、验证码下发、当前用户查询，
 * 以及供服务间调用的余额扣减与退款入账。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface IUserService extends IService<User> {

    /** 用户名 + 密码登录 */
    UserLoginVO login(LoginFormDTO loginFormDTO);

    /** 发送手机验证码：60 秒频控，验证码 5 分钟有效 */
    void sendCode(String phone);

    /** 手机验证码登录：未注册手机号自动注册 */
    UserLoginVO loginByCode(SmsLoginDTO smsLoginDTO);

    /** 获取当前登录用户（身份来自网关注入的 user-info 头，经拦截器放入 UserContext） */
    UserLoginVO currentUser();

    /**
     * 扣减余额（余额支付 / 付费券购买）。
     *
     * 【注意】幂等键 bizNo 强制必填，为空直接报错；口径由调用方决定，同一逻辑动作必须复用同一个业务号：
     * {@code PAY:<支付单号>:<请求 traceId>}、{@code VOUCHER-<券id>-<用户id>}、{@code VOUCHER-SECKILL-<token>}。
     */
    void deductMoney(String pw, Integer totalFee, String bizNo);

    /**
     * 退款入账（内部接口，供交易域的退款闭环调用）：把 amount 分加回 userId 的余额。
     *
     * 只做"加钱"，不判断"该不该退"：退款是否成立属于交易域的业务判断（订单归属、订单状态、是否已退）。
     *
     * @param bizNo 幂等键（同样是强制的）：{@code ORDER_REFUND:<订单id>}、{@code PAYFAIL:<扣款业务号>}、
     *              {@code SECKILL_REFUND:<token>}；同一笔退款重复调用只入账一次。
     */
    void refundMoney(Long userId, Integer amount, String bizNo);
}
