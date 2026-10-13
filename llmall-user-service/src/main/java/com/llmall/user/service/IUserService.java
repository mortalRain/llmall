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

    /**
     * 扣减余额（余额支付 / 付费券购买）。
     *
     * <p>【bizNo 是幂等键，不是备注】每一次"应当只扣一次"的资金动作都必须带一个业务号：
     * 它会被写进 {@code balance_flow} 的唯一键 {@code uk_biz(biz_type, biz_no)}，
     * 于是上游重试、Feign 重发、并发双击的第二次到达会被数据库挡下（而不是靠 Redis 锁到期前的运气）。
     * 所以它必须是**强制的**：为空直接报错（fail-closed），不给"忘了传就退化成非幂等"的机会。
     *
     * <p>业务号的口径（由调用方决定，同一个逻辑动作必须用同一个号）：
     * {@code PAY:<支付单号>:<请求 traceId>}（一次支付尝试）、
     * {@code VOUCHER-<券id>-<用户id>}（普通付费券购买）、
     * {@code VOUCHER-SECKILL-<token>}（秒杀抢券）。
     */
    void deductMoney(String pw, Integer totalFee, String bizNo);

    /**
     * 退款入账（内部接口，供交易域的退款闭环调用）：把 amount 分加回 userId 的余额。
     *
     * 只做"加钱"这一件事，不在这里判断"该不该退"：退款是否成立属于交易域的业务判断
     * （订单归属、订单状态、是否已退），用户域只回答"钱有没有到账"。
     * 职责分开的好处是：交易域可以在余额退回失败时按自己的事务语义做补偿（把订单改回已支付），
     * 而不是把订单状态的知识塞进用户域。
     *
     * @param bizNo 幂等键（同样是强制的）：{@code ORDER_REFUND:<订单id>}、{@code PAYFAIL:<扣款业务号>}、
     *              {@code SECKILL_REFUND:<token>} 等。同一笔退款被重复调用时只入账一次 ——
     *              这正是"扣款后失败要自动退款"能安全重试的前提。
     */
    void refundMoney(Long userId, Integer amount, String bizNo);
}
