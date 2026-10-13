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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 用户服务实现。
 *
 * 合并要点：把点评体系的"手机号 + 验证码登录"与商城体系的"用户名 + 密码登录"
 * 收敛到同一张 user 表、同一套令牌签发逻辑（ITokenService），
 * 而不是两套账号、两套 token 逻辑各写一遍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration CODE_SEND_INTERVAL = Duration.ofSeconds(60);

    private final PasswordEncoder passwordEncoder;

    /**
     * 演示环境统一的初始密码（同时作为余额支付的"支付密码"）。
     *
     * 背景：余额支付校验的是账号密码（llmall-pay-service → llmall-user-service deductMoney），
     * 而短信验证码登录会自动注册新账号 —— 原来给的是随机串，用户不可能知道，
     * 结果"短信注册的账号永远无法用余额支付"。这里统一初始化为演示默认值，
     * 生产环境应改为"首次支付前引导设置支付密码"。
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
        // 频控：60 秒内只能发一次，防止被当成短信轰炸机
        String limitKey = AuthConstants.REDIS_CODE_LIMIT + phone;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(limitKey, "1", CODE_SEND_INTERVAL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BizIllegalException("验证码发送过于频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(AuthConstants.REDIS_LOGIN_CODE + phone, code, CODE_TTL);
        // 真实项目这里调用短信服务；本地环境把验证码打到日志便于联调与演示
        // （前端提示语就是"验证码已发送（见 llmall-user-service 日志）"，属于有意保留的演示通道）。
        // 【脱敏】手机号按"前 3 后 4"打码：日志里出现完整手机号等于把用户 PII 复制到
        // 日志采集/巡检/工单截图里，而定位问题只需要知道"是哪条请求"，打码后依然可辨认。
        log.info("【验证码】手机号 {} 的登录验证码为 {}（{} 分钟内有效）", maskPhone(phone), code, CODE_TTL.toMinutes());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserLoginVO loginByCode(SmsLoginDTO smsLoginDTO) {
        String phone = smsLoginDTO.getPhone();
        String codeKey = AuthConstants.REDIS_LOGIN_CODE + phone;
        String cached = redisTemplate.opsForValue().get(codeKey);
        if (cached == null) {
            throw new BizIllegalException("验证码已过期，请重新获取");
        }
        if (!cached.equals(smsLoginDTO.getCode())) {
            throw new BizIllegalException("验证码错误");
        }
        // 验证码一次性使用：校验通过立即删除，防止被重放
        redisTemplate.delete(codeKey);

        User user = lambdaQuery().eq(User::getPhone, phone).one();
        if (user == null) {
            // 首次登录自动注册（点评体系的习惯），用户名用手机号派生，保证唯一
            // 注意：user 表是课程老库的表，create_time/update_time 是 NOT NULL 且"无默认值"
            // （其他表在 sql/merged 里都加了 DEFAULT CURRENT_TIMESTAMP，这张表没有），
            // 不显式赋值就会报 "Field 'create_time' doesn't have a default value"。
            // 密码：余额支付用的是这个密码（llmall-pay-service → llmall-user-service deductMoney 校验），
            // 原来给随机串会导致"短信注册的用户永远无法用余额支付"，这里统一初始化为演示默认值，
            // 生产环境应改为"首次支付前引导设置支付密码"。
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
            // 正常不会走到这里（网关已拦截未登录请求），出现即说明内部调用漏传了用户上下文
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
     * <h3>为什么"先插流水再扣款"，而不是反过来</h3>
     * 并发下两个请求会同时通过"查一下这笔业务号扣过没有"这道检查 —— 只有唯一键能真正挡住第二个。
     * 先占位的好处是第二个请求**一分钱都不动**就退出；反过来写（先扣款再插流水）必须靠"唯一键冲突 →
     * 抛异常 → 整个事务回滚"才能避免重复扣款，多走一次回滚、日志也难读。
     * 余额不足时 ① 的占位会随事务一起回滚，不会留下"有扣款流水但钱没扣"的假账。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductMoney(String pw, Integer totalFee, String bizNo) {
        Long userId = UserContext.getUser();
        log.info("开始扣款: userId={}, amount={}, bizNo={}", userId, totalFee, bizNo);
        if (totalFee == null || totalFee <= 0) {
            throw new BizIllegalException("支付金额非法");
        }
        // 幂等键是强制的（fail-closed）：没有它，上游重试/并发双击就会重复扣款，
        // 事后连"哪一笔对应哪次支付"都分不清。宁可报错，也不退化成非幂等的扣款。
        if (StrUtil.isBlank(bizNo)) {
            throw new BadRequestException("扣款必须携带业务号（幂等键）");
        }
        User user = getById(userId);
        if (user == null) {
            throw new BizIllegalException("用户不存在");
        }
        if (!passwordEncoder.matches(pw, user.getPassword())) {
            // 【提示语修复】原来的"用户密码错误"没告诉用户错在哪（余额支付用的是"支付密码"，
            // 短信验证码登录自动注册的账号密码是随机串，用户根本不可能知道）。
            // 这里明确说明是支付密码，并给出演示环境的默认值。
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
            // 幂等命中：同一业务号已经扣过款（上游重试、Feign 重发、消息重投都会走到这里）——不是错误
            log.info("该业务号已完成扣款，本次幂等跳过（未扣款）: userId={}, bizNo={}, amount={}",
                    userId, bizNo, totalFee);
            return;
        }

        // ② 条件更新（`balance >= amount` 写在 SQL 里），按受影响行数判断：余额不足时不会扣成负数
        int rows = baseMapper.updateMoney(userId, totalFee);
        if (rows == 0) {
            throw new BizIllegalException("余额不足，当前余额 " + (balanceBefore / 100.0) + " 元，本单应付 " + (totalFee / 100.0) + " 元");
        }

        // ③ 修正账本：并发场景下"扣款前余额 - 金额"未必等于最终值，账本里的 balance_after 必须是真实值，
        //    否则对账时 SUM(change_amount) 与余额对不上，账本就成了误导
        User after = getById(userId);
        if (after != null && flowId != null) {
            balanceFlowMapper.updateById(new BalanceFlow().setId(flowId).setBalanceAfter(after.getBalance()));
        }
        log.info("扣款成功: userId={}, amount={}, bizNo={}, 扣款后余额={}",
                userId, totalFee, bizNo, after == null ? null : after.getBalance());
    }

    /**
     * 退款入账：把订单实付金额加回用户余额，同样以 bizNo 幂等。
     *
     * <h3>为什么"谁的钱"要用参数传、又在方法内再校验一次</h3>
     * 退款有两种触发方：用户自己在交易域发起（有登录态，Feign 会透传 user-info），
     * 以及超时/后台任务发起（MQ 线程没有登录态）。所以 userId 必须能显式传进来。
     * 但这留下一个越权面：外部用户完全可以自己打这个接口，指定 userId 给别人/自己加钱。
     * 因此这里加一条防线 —— **有登录上下文时，只允许操作自己的账户**：
     * 网关会剥离客户端伪造的 user-info 头，所以外部请求必然带着请求者自己的 id，
     * 拿别人的 id 调就会被 403 挡住；而内部调用（无 user-info 头）不受影响。
     * 换句话说，把"越权判定"放在用户域而不是只靠"接口是内部的"这种口头约定上。
     *
     * <h3>退款幂等为什么是"扣款后失败自动退款"能安全重试的前提</h3>
     * 支付流程失败时的补偿退款可能被触发两次（方法内补偿 + 事务回滚后的补偿），
     * 同一个 bizNo 的第二次会在唯一键上被挡下 —— 否则用户会收到两倍退款。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refundMoney(Long userId, Integer amount, String bizNo) {
        if (userId == null) {
            throw new BadRequestException("退款用户不能为空");
        }
        // 金额必须为正：负数退款等于从用户余额里扣钱，且会绕过扣款接口的余额充足性校验（可能扣成负数）
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

    /** 昵称脱敏：138****1234 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
