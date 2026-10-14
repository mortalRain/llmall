package com.llmall.user.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.UserContext;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.po.BalanceFlow;
import com.llmall.user.domain.po.User;
import com.llmall.user.domain.po.UserInfo;
import com.llmall.user.domain.vo.TokenPair;
import com.llmall.user.domain.vo.UserLoginVO;
import com.llmall.user.enums.UserStatus;
import com.llmall.user.mapper.BalanceFlowMapper;
import com.llmall.user.mapper.UserInfoMapper;
import com.llmall.user.mapper.UserMapper;
import com.llmall.user.service.ITokenService;
import com.llmall.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 用户域核心：把点评体系的"手机号 + 验证码登录"与商城体系的"用户名 + 密码登录"收敛到
 * 同一张 user 表、同一套令牌签发逻辑（ITokenService）。
 *
 * 【注意】余额支付的"支付密码"与登录密码是同一列（user.password）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration CODE_SEND_INTERVAL = Duration.ofSeconds(60);

    /** 核销脚本返回值：核销成功 */
    private static final long CODE_CONSUME_OK = 1L;
    /** 核销脚本返回值：验证码不存在或已过期 */
    private static final long CODE_CONSUME_MISSING = 0L;
    /** 核销脚本返回值：验证码存在但不是用户提交的这个 */
    private static final long CODE_CONSUME_MISMATCH = -1L;
    /** 核销脚本返回值：值相等却没删掉 → 被另一个并发请求抢先核销 */
    private static final long CODE_CONSUME_RACED = -2L;

    /**
     * 验证码核销脚本：把"读值 → 比对 → 删除"合成一次原子操作，保证验证码一次性使用。
     *
     * 【注意】不用现成的 compare-and-delete，是因为它只回答"删没删掉"，
     * 把"已过期 / 填错码 / 被并发抢先核销"混成同一个返回值，而这三者该给的提示语不同。
     * KEYS[1]=验证码 key；ARGV[1]=用户提交的验证码。
     */
    private static final DefaultRedisScript<Long> CONSUME_CODE_SCRIPT = new DefaultRedisScript<>(
            "local actual = redis.call('get', KEYS[1]) "
                    + "if not actual then return 0 end "
                    + "if actual ~= ARGV[1] then return -1 end "
                    + "if redis.call('del', KEYS[1]) == 1 then return 1 else return -2 end",
            Long.class);

    private final PasswordEncoder passwordEncoder;

    /**
     * 演示环境统一的初始口令：短信验证码登录自动注册的账号用它初始化。
     *
     * 【注意】余额支付校验的"支付密码"就是这一列密码（llmall-pay-service 调用 deductMoney 时传入），
     * 该值需要用户可预期，否则新注册账号用不了余额支付。
     */
    private static final String DEFAULT_PAY_PASSWORD = "123456";
    private final ITokenService tokenService;
    private final StringRedisTemplate redisTemplate;
    private final UserInfoMapper userInfoMapper;
    /** 资金账本（余额流水）：每次余额变动都与余额更新写在同一事务里，见 deductMoney/refundMoney */
    private final BalanceFlowMapper balanceFlowMapper;

    @Override
    public UserLoginVO login(LoginFormDTO loginDTO) {
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        User user = lambdaQuery().eq(User::getUsername, username).one();
        // 安全细节：用户不存在与密码错误返回同一句提示，避免账号枚举
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BadRequestException("用户名或密码错误");
        }
        assertNotFrozen(user);
        return buildLoginVO(user);
    }

    @Override
    public void sendCode(String phone) {
        // 频控：60 秒内只能发一次（防短信轰炸）
        String limitKey = AuthConstants.REDIS_CODE_LIMIT + phone;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(limitKey, "1", CODE_SEND_INTERVAL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BizIllegalException("验证码发送过于频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(AuthConstants.REDIS_LOGIN_CODE + phone, code, CODE_TTL);
        // 本地环境不接短信通道，验证码打到日志（前端提示语即"验证码已发送（见 llmall-user-service 日志）"）
        // 【脱敏】手机号按"前 3 后 4"打码，避免完整手机号进入日志采集与工单截图
        log.info("【验证码】手机号 {} 的登录验证码为 {}（{} 分钟内有效）", maskPhone(phone), code, CODE_TTL.toMinutes());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserLoginVO loginByCode(SmsLoginDTO smsLoginDTO) {
        String phone = smsLoginDTO.getPhone();
        String codeKey = AuthConstants.REDIS_LOGIN_CODE + phone;
        String submitted = smsLoginDTO.getCode();
        if (StrUtil.isBlank(submitted)) {
            throw new BizIllegalException("验证码不能为空");
        }
        // 验证码是登录凭证，核销必须一次原子完成"读值 + 比对 + 删除"（见 CONSUME_CODE_SCRIPT）；
        // 拆成 GET → 比对 → DEL 三步时，并发请求会各自读到值并都通过比对，同一验证码等于可重放
        Long outcome;
        try {
            outcome = redisTemplate.execute(CONSUME_CODE_SCRIPT,
                    Collections.singletonList(codeKey), submitted);
        } catch (Exception e) {
            // 认证链路 fail-closed：Redis 不可用时不能"跳过验证码校验"放行
            log.error("校验验证码失败（按不通过处理）: phone={}", maskPhone(phone), e);
            throw new BizIllegalException("验证码校验失败，请稍后重试");
        }
        if (outcome == null || outcome == CODE_CONSUME_MISSING) {
            throw new BizIllegalException("验证码已过期，请重新获取");
        }
        if (outcome == CODE_CONSUME_MISMATCH) {
            throw new BizIllegalException("验证码错误");
        }
        if (outcome == CODE_CONSUME_RACED) {
            log.warn("验证码已被并发核销（疑似重放）: phone={}", maskPhone(phone));
            throw new BizIllegalException("验证码已被使用，请重新获取");
        }
        if (outcome != CODE_CONSUME_OK) {
            throw new BizIllegalException("验证码校验失败，请重新获取");
        }

        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if (user == null) {
            // 首次登录自动注册，用户名由手机号派生以保证唯一
            // 【注意】user 表 create_time/update_time 是 NOT NULL 且无默认值，必须显式赋值，
            // 否则报 "Field 'create_time' doesn't have a default value"
            LocalDateTime now = LocalDateTime.now();
            user = new User()
                    .setUsername("u_" + phone)
                    .setPhone(phone)
                    .setNickName(maskPhone(phone))
                    .setPassword(passwordEncoder.encode(DEFAULT_PAY_PASSWORD))
                    .setStatus(UserStatus.NORMAL)
                    .setBalance(500000)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            save(user);
            log.info("手机号 {} 首次登录，已自动注册: userId={}", maskPhone(phone), user.getId());
        }
        assertNotFrozen(user);
        return buildLoginVO(user);
    }

    @Override
    public UserLoginVO currentUser() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            // 网关已拦截未登录请求，走到这里说明内部调用漏传了用户上下文
            throw new UnauthorizedException("未登录");
        }
        User user = getById(userId);
        if (user == null) {
            throw new UnauthorizedException("用户不存在");
        }
        return toVO(user);
    }

    /**
     * 扣款：幂等键（bizNo）占位 → 条件更新余额 → 用真实余额修正流水，三步同一事务。
     *
     * 【注意】必须先插流水占位、再扣款：并发下"查一下这笔业务号扣过没有"挡不住第二个请求，
     * 只有流水唯一键能挡住，且第二个请求一分钱不动就退出；余额不足时占位随事务回滚，
     * 不会留下"有扣款流水但钱没扣"的假账。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductMoney(String pw, Integer totalFee, String bizNo) {
        Long userId = UserContext.getUser();
        log.info("开始扣款: userId={}, amount={}, bizNo={}", userId, totalFee, bizNo);
        if (totalFee == null || totalFee <= 0) {
            throw new BizIllegalException("支付金额非法");
        }
        // 幂等键强制（fail-closed）：缺了它，上游重试/并发双击就会重复扣款，事后也分不清"哪一笔对应哪次支付"
        if (StrUtil.isBlank(bizNo)) {
            throw new BadRequestException("扣款必须携带业务号（幂等键）");
        }
        User user = getById(userId);
        if (user == null) {
            throw new BizIllegalException("用户不存在");
        }
        if (!passwordEncoder.matches(pw, user.getPassword())) {
            // 提示语点明是"支付密码"（与登录密码同一列），并给出演示环境默认值
            throw new BizIllegalException("支付密码错误（演示环境各账号的支付密码默认 " + DEFAULT_PAY_PASSWORD + "）");
        }

        int balanceBefore = user.getBalance() == null ? 0 : user.getBalance();
        Long flowId;
        try {
            // ① 占位：balance_after 此刻只能按"扣款前余额 - 金额"估算，③ 会用真实余额修正
            BalanceFlow flow = new BalanceFlow()
                    .setUserId(userId)
                    .setChangeAmount(-totalFee)
                    .setBalanceAfter(balanceBefore - totalFee)
                    .setBizType(BalanceFlow.TYPE_DEDUCT)
                    .setBizNo(bizNo)
                    .setRemark("余额支付");
            balanceFlowMapper.insert(flow);
            flowId = flow.getId();
        } catch (DuplicateKeyException e) {
            // 幂等命中：同一业务号已扣过款（上游重试、Feign 重发、消息重投），不是错误
            log.info("该业务号已完成扣款，本次幂等跳过（未扣款）: userId={}, bizNo={}, amount={}",
                    userId, bizNo, totalFee);
            return;
        }

        // ② 条件更新（`balance >= amount` 写在 SQL 里），按受影响行数判断：余额不足时不会扣成负数
        int rows = baseMapper.updateMoney(userId, totalFee);
        if (rows == 0) {
            throw new BizIllegalException("余额不足，当前余额 " + (balanceBefore / 100.0) + " 元，本单应付 " + (totalFee / 100.0) + " 元");
        }

        // ③ 修正账本：并发下"扣款前余额 - 金额"未必等于最终值，balance_after 必须写真实余额，
        //    否则对账时 SUM(change_amount) 与余额对不上
        User after = getById(userId);
        if (after != null && flowId != null) {
            balanceFlowMapper.updateById(new BalanceFlow().setId(flowId).setBalanceAfter(after.getBalance()));
        }
        log.info("扣款成功: userId={}, amount={}, bizNo={}, 扣款后余额={}",
                userId, totalFee, bizNo, after == null ? null : after.getBalance());
    }

    /**
     * 退款入账：把订单实付金额加回用户余额，同样以 bizNo 幂等 —— 同一 bizNo 的第二次入账会被流水唯一键
     * 挡下，"扣款后失败自动退款"这类补偿因此可以安全重试，否则用户会收到两倍退款。
     *
     * 【注意】userId 必须由参数显式传入：超时/后台任务在 MQ 线程里没有登录态。
     * 但参数化会留下越权面（外部请求可以指定 userId 给别人加钱），所以有登录上下文时只允许操作自己
     * 的账户：网关会剥离客户端伪造的 user-info 头，拿别人的 id 调必被 403 挡住，内部调用不受影响。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundMoney(Long userId, Integer amount, String bizNo) {
        if (userId == null) {
            throw new BadRequestException("退款用户不能为空");
        }
        // 金额必须为正：负数退款等于从用户余额里扣钱，且绕过扣款接口的余额充足性校验（可能扣成负数）
        if (amount == null || amount <= 0) {
            throw new BizIllegalException("退款金额必须大于 0");
        }
        if (StrUtil.isBlank(bizNo)) {
            throw new BadRequestException("退款必须携带业务号（幂等键）");
        }
        Long currentUser = UserContext.getUser();
        if (currentUser != null && !currentUser.equals(userId)) {
            throw new ForbiddenException("无权操作他人账户余额");
        }

        Long flowId;
        try {
            BalanceFlow flow = new BalanceFlow()
                    .setUserId(userId)
                    .setChangeAmount(amount)
                    .setBalanceAfter(0)
                    .setBizType(BalanceFlow.TYPE_REFUND)
                    .setBizNo(bizNo)
                    .setRemark("退款入账");
            balanceFlowMapper.insert(flow);
            flowId = flow.getId();
        } catch (DuplicateKeyException e) {
            log.info("该业务号已完成退款入账，本次幂等跳过（未重复加钱）: userId={}, bizNo={}, amount={}",
                    userId, bizNo, amount);
            return;
        }

        // 条件更新 + 按受影响行数判断：用户不存在时不报"成功"，避免上游以为钱已经到账
        int rows = baseMapper.refundMoney(userId, amount);
        if (rows == 0) {
            throw new BizIllegalException("退款失败：用户不存在");
        }
        User after = getById(userId);
        if (after != null && flowId != null) {
            balanceFlowMapper.updateById(new BalanceFlow().setId(flowId).setBalanceAfter(after.getBalance()));
        }
        log.info("退款入账成功: userId={}, amount={}分, bizNo={}, 退款后余额={}",
                userId, amount, bizNo, after == null ? null : after.getBalance());
    }

    private void assertNotFrozen(User user) {
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new ForbiddenException("用户被冻结");
        }
    }

    /** 统一组装登录返回：两条登录通道共用同一套令牌签发逻辑 */
    private UserLoginVO buildLoginVO(User user) {
        TokenPair pair = tokenService.issue(user.getId());
        UserLoginVO vo = toVO(user);
        vo.setToken(pair.getAccessToken());
        vo.setAccessToken(pair.getAccessToken());
        vo.setRefreshToken(pair.getRefreshToken());
        return vo;
    }

    /** 用户视图组装（不含令牌），登录与"获取当前用户"共用 */
    private UserLoginVO toVO(User user) {
        UserLoginVO vo = new UserLoginVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickName(StrUtil.blankToDefault(user.getNickName(), user.getUsername()));
        vo.setIcon(user.getIcon());
        vo.setBalance(user.getBalance());
        UserInfo info = userInfoMapper.selectById(user.getId());
        if (info != null) {
            vo.setCredits(info.getCredits());
            vo.setLevel(info.getLevel());
        }
        return vo;
    }

    /** 脱敏格式：138****1234 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
