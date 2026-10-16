package com.llmall.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.LoginPasswordFormDTO;
import com.llmall.user.domain.dto.PayPasswordFormDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.po.User;
import com.llmall.user.domain.vo.UserLoginVO;

/**
 * 用户账号与余额服务契约：两种登录方式（用户名密码 / 手机验证码）、验证码下发、当前用户查询，
 * 以及供服务间调用的余额扣减与退款入账。
 *
 * 【契约与本实现的边界】本接口只写功能、入参、返回与异常；
 * 口令分层、改密校验成本排序、幂等键口径等设计决策见 UserServiceImpl。
 */
public interface IUserService extends IService<User> {

    /** 用户名 + 密码登录：返回令牌对、用户视图与改密标记。 */
    UserLoginVO login(LoginFormDTO loginFormDTO);

    /** 发送手机验证码：同一手机号在发送间隔内只允许一次，验证码在有效期内只能核销一次。 */
    void sendCode(String phone);

    /** 手机验证码登录：手机号未注册时自动注册（用户名由手机号派生，登录口令为随机值）。 */
    UserLoginVO loginByCode(SmsLoginDTO smsLoginDTO);

    /** 获取当前登录用户（身份来自网关注入的 user-info 头，经拦截器放入 UserContext） */
    UserLoginVO currentUser();

    /**
     * 扣减余额（余额支付 / 付费券购买），供支付域调用。
     *
     * 【入参约束】totalFee 单位为分且必须 > 0；bizNo 为强制必填的幂等键，
     * 同一逻辑动作必须复用同一个业务号（口径与格式集中定义在 llmall-common 的 IdempotentKeys，禁止各处手拼）。
     * 【返回】无返回值；同一 bizNo 重复调用不重复扣款（幂等命中直接返回）。
     * 【异常】BadRequestException：金额非法、bizNo 为空；
     *        BizIllegalException：支付口令错误、余额不足、账号仍在使用初始支付口令；
     *        UnauthorizedException：无登录上下文。
     * 【风险】幂等键必须是服务端产生且确定的值，禁止使用客户端可伪造的 traceId 或按次随机值
     * （随机键会让"扣款成功但响应丢失"被误判为未扣，重试即重复扣款）。
     *
     * @param pw      支付口令（明文，仅在请求体/内部调用中传递，不落库）
     * @param totalFee 扣款金额（分），必须 &gt; 0
     * @param bizNo   幂等键，强制必填
     * @throws com.llmall.common.exception.BadRequestException 参数为空/非法
     * @throws com.llmall.common.exception.BizIllegalException 支付口令错误、余额不足、或仍在使用初始支付口令
     */
    void deductMoney(String pw, Integer totalFee, String bizNo);

    /**
     * 修改/设置支付口令（供余额支付使用）。
     *
     * 【入参约束】见 PayPasswordFormDTO，三个字段均必填，新口令长度 6~32 位；
     * 初始口令账号是把初始值当"原口令"传入（服务端不区分设置与修改）。
     * 【返回】无返回值；成功后该用户全部会话立即失效，调用方须引导重新登录。
     * 【异常】UnauthorizedException：未登录；
     *        BizIllegalException：请求过于频繁、账号处于口令锁定中、验证码错误或已过期、
     *        原支付口令错误、新旧口令相同。
     */
    void changePayPassword(PayPasswordFormDTO form);

    /**
     * 修改登录口令（供"用户名 + 密码"登录使用）。
     *
     * 【入参约束】见 LoginPasswordFormDTO，三个字段均必填，新口令长度 6~32 位。
     * 【返回】无返回值；成功后该用户全部会话立即失效，调用方须引导重新登录。
     * 【异常】UnauthorizedException：未登录；
     *        BizIllegalException：请求过于频繁、账号处于口令锁定中、验证码错误或已过期、
     *        原登录口令错误、新旧口令相同。
     */
    void changeLoginPassword(LoginPasswordFormDTO form);

    /**
     * 查询当前账号的支付口令锁定状态，供前端提前展示"还需等待多久"。
     *
     * 【返回】locked=false 且 remainMinutes=0 表示未锁定；locked=true 时 remainMinutes 为
     * 剩余锁定分钟数（向上取整，最小 1）。返回值不含"还剩几次机会"。
     * 【异常】UnauthorizedException：未登录。
     */
    com.llmall.user.domain.vo.PwdLockStatusVO queryPayPasswordLockStatus();

    /**
     * 退款入账（内部接口，供交易域的退款闭环调用）：把 amount 分加回 userId 的余额。
     *
     * 【入参约束】userId 必填；amount 单位为分且必须大于 0；bizNo 为强制必填的幂等键，
     * 同一笔退款重复调用只入账一次（口径集中定义在 IdempotentKeys）。
     * 【返回】无返回值；只做"加钱"，不判断"该不该退" —— 退款是否成立属交易域的业务判断。
     * 【异常】BadRequestException：userId/amount/bizNo 为空或不合法；
     *        ForbiddenException：带登录态且操作的不是本人账户；
     *        BizIllegalException：目标用户不存在。
     */
    void refundMoney(Long userId, Integer amount, String bizNo);
}
