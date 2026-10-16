package com.llmall.user.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.common.constant.AuthConstants;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.trace.BizTrace;
import com.llmall.common.utils.UserContext;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.user.domain.dto.LoginFormDTO;
import com.llmall.user.domain.dto.LoginPasswordFormDTO;
import com.llmall.user.domain.dto.PayPasswordFormDTO;
import com.llmall.user.domain.dto.SmsLoginDTO;
import com.llmall.user.domain.po.BalanceFlow;
import com.llmall.user.domain.po.User;
import com.llmall.user.domain.po.UserInfo;
import com.llmall.user.domain.vo.PwdLockStatusVO;
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
 * 用户账号入口：两种登录方式（用户名密码 / 手机验证码）、账号口令的修改与弱口令收口，
 * 以及供服务间调用的余额扣减与退款入账。
 *
 * 【两种登录方式共用一套账号】手机验证码登录与用户名密码登录落在同一张 user 表、
 * 同一套令牌签发逻辑（{@link ITokenService}），因此两条通道的冻结校验与弱口令校验必须同一口径。
 *
 * 【口令职责分离】user 表用两列分别存两种口令，两列禁止互相读写：
 * 1. password（登录口令）：只在 login 校验，只由 changeLoginPassword 与自动注册写入；
 * 2. pay_password（支付口令）：只经 UserMapper.selectPayPassword 取出，在 deductMoney 与
 *    changePayPassword 校验，只由 changePayPassword 与自动注册写入。
 *
 * 【为什么必须分两列】两种口令的强度要求相反：登录口令必须不可预期（自动注册用随机值，
 * 否则"用户名由手机号推导 + 口令固定"就等于知道手机号即可接管账号）；
 * 支付口令必须用户可预期（否则自动注册的账号无法完成余额支付）。合为一列时两个要求直接冲突。
 *
 * 【注意】任一侧退化为读写另一列，都会让改一种口令顺带改变另一种口令的能力：
 * 例如支付校验误读 password，用户改登录口令即等于改掉支付口令。
 *
 * 【迁移路径】拆列由 sql/merged/60-user-pay-password.sql 落地：ADD COLUMN（明文占位默认值）
 * 与随后把占位值改写为 BCrypt 哈希的 UPDATE 两步都不可跳过，缺第二步会让存量账号的余额支付
 * 因"明文与哈希对不上"而不可用。存量账号的登录口令仍是固定值，重置须按该脚本"上线必做"第 3 条人工执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration CODE_SEND_INTERVAL = Duration.ofSeconds(60);

    /**
     * 初始口令：自动注册账号的支付口令初值，同时也是判断"支付口令是否仍是初始值"的判据。
     *
     * 【为什么支付口令要有可预期的初值】余额支付需要用户知道口令，否则自动注册的账号无法完成支付；
     * 登录口令的取向相反，见 {@link #randomLoginPassword()}。
     *
     * 【两个用途，改名时务必一并检查】
     * 1. 写入：自动注册时作为支付口令（{@code loginByCode}）；
     * 2. 比对：判断账号是否仍在使用初始口令，产出强制改密标记
     *    （{@link #isUsingDefaultPayPassword} / {@link #isUsingDefaultLoginPassword}），
     *    并据此在余额支付入口拦截（{@code deductMoney}）。
     *
     * 【注意】两个用途必须同源：一旦把写入值与比对值拆成两个常量，只改其中之一就会静默出现
     * "明明没改过口令却不再被要求改密"，即强制改密失效。
     */
    private static final String INITIAL_PASSWORD = "123456";

    /** 手机号格式（与 Controller 的 @Pattern 同一口径）：service 层是"自动注册"的权威校验点，不能被绕过 */
    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 弱口令账号的缓冲期：期内可正常登录并持续提示改密，到期仍未修改才在登录阶段硬拦截。
     *
     * 【为什么需要缓冲期】存量弱口令账号不能一上线就拦死：用户可能长期只走短信登录，
     * 突然无法登录会被当成故障并冲击客服。缓冲期让用户有主动改密的窗口。
     *
     * 【为什么起点是"首次被检测到"而不是"账号创建时间"】用账号创建时间会让所有存量账号
     * 在上线当天即到期，等于没有缓冲期；从首次检测起算才能保证每个账号都获得完整缓冲期，
     * 且到期时间自然错开，避免集中到期。
     */
    private static final Duration WEAK_PASSWORD_GRACE = Duration.ofDays(7);

    /** 口令错误锁定阈值与锁定时长 */
    private static final int PWD_MAX_FAIL = 5;
    private static final Duration PWD_LOCK_DURATION = Duration.ofMinutes(30);
    /** 口令错误计数的保留时间：比锁定时长略长，使"锁定解除后计数也重置" */
    private static final Duration PWD_FAIL_TTL = Duration.ofMinutes(31);

    /** 口令修改接口频控：同一账号 1 小时内最多 5 次 */
    private static final int PWD_CHANGE_MAX_PER_HOUR = 5;
    private static final Duration PWD_CHANGE_WINDOW = Duration.ofHours(1);

    /** 四段式埋点的业务类型标识（见 llmall-common 的 BizTrace） */
    private static final String BIZ_TYPE_LOGIN = "LOGIN";

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

    private final ITokenService tokenService;
    private final StringRedisTemplate redisTemplate;
    private final UserInfoMapper userInfoMapper;
    /** 资金账本（余额流水）：每次余额变动都与余额更新写在同一事务里，见 deductMoney/refundMoney */
    private final BalanceFlowMapper balanceFlowMapper;

    @Override
    public UserLoginVO login(LoginFormDTO loginDTO) {
        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();
        // 四段式埋点：用 username 作 bizId 而不是 userId —— 登录失败时还不知道 userId，
        // 而"某个用户名一直在失败"正是需要被发现的安全信号
        long traceStart = BizTrace.enter(BIZ_TYPE_LOGIN, username, "方式=密码登录");
        User user = lambdaQuery().eq(User::getUsername, username).one();
        // 安全细节：用户不存在与密码错误返回同一句提示，避免账号枚举
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            BizTrace.result(BIZ_TYPE_LOGIN, username, false, "用户名或密码错误（不区分二者，避免账号枚举）");
            BizTrace.cost(BIZ_TYPE_LOGIN, username, traceStart);
            throw new BadRequestException("用户名或密码错误");
        }
        assertNotFrozen(user);
        // 弱口令缓冲期：期内放行并提示改密，到期后硬拦截（两种登录方式口径一致）
        assertLoginAllowedForWeakPassword(user);
        UserLoginVO vo = buildLoginVO(user);
        BizTrace.result(BIZ_TYPE_LOGIN, username, true,
                "登录成功 userId=" + user.getId() + " 需改密=" + vo.getNeedChangePassword());
        BizTrace.cost(BIZ_TYPE_LOGIN, username, traceStart);
        return vo;
    }

    @Override
    public void sendCode(String phone) {
        // 频控：发送间隔内只允许一次（防短信轰炸）
        String limitKey = AuthConstants.REDIS_CODE_LIMIT + phone;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(limitKey, "1", CODE_SEND_INTERVAL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BizIllegalException("验证码发送过于频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue().set(AuthConstants.REDIS_LOGIN_CODE + phone, code, CODE_TTL);
        // 【验证码与日志】本行把验证码明文写入日志，且当前没有任何开关约束它：
        // 只有不接短信通道的本地/联调环境可以接受，生产必须走真实短信通道并关闭本行 ——
        // 生产误开等于把任意手机号的登录验证码写进日志采集链路，任何有日志读权限的人都能据此登录对应账号。
        // 【脱敏】手机号按"前 3 后 4"打码，避免完整手机号进入日志采集与工单截图
        log.info("【验证码】手机号 {} 的登录验证码为 {}（{} 分钟内有效）", maskPhone(phone), code, CODE_TTL.toMinutes());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserLoginVO loginByCode(SmsLoginDTO smsLoginDTO) {
        // 手机号格式校验必须在 service 层再做一次：本接口是"未注册自动注册"的入口，
        // 非法/空手机号会直接拼进 Redis key 与 user.phone（uk_phone），
        // 前者产生垃圾 key，后者在 NOT NULL/唯一键上失败并变成 500 而不是 400
        String phone = smsLoginDTO.getPhone();
        if (StrUtil.isBlank(phone)) {
            throw new BadRequestException("手机号不能为空");
        }
        phone = phone.trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw new BadRequestException("手机号格式不正确");
        }
        String submitted = smsLoginDTO.getCode();
        long traceStart = BizTrace.enter(BIZ_TYPE_LOGIN, phone, "方式=短信验证码登录");
        // 验证码核销抽成公共方法：登录与"修改支付密码"共用同一套验证码与同一套核销语义，
        // 避免两处各写一遍 CONSUME_CODE_SCRIPT 的分支判断而出现口径漂移
        consumeLoginCode(phone, submitted);
        BizTrace.idempotent(BIZ_TYPE_LOGIN, phone, AuthConstants.REDIS_LOGIN_CODE + phone,
                "验证码已原子核销（读值+比对+删除一次完成）");

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
                    // 登录口令：随机生成且不返回给任何人 —— 用户名由手机号派生（u_{手机号}）属于公开可推导的值，
                    // 口令不可预期才能让"知道手机号"不等于"能登录该账号"；改成固定值等于开放账号接管路径
                    .setPassword(passwordEncoder.encode(randomLoginPassword()))
                    // 支付口令：单独一列，保留可预期的初始值，否则自动注册的账号无法完成余额支付
                    .setPayPassword(passwordEncoder.encode(INITIAL_PASSWORD))
                    .setStatus(UserStatus.NORMAL)
                    .setBalance(500000)
                    .setCreateTime(now)
                    .setUpdateTime(now);
            save(user);
            log.info("手机号 {} 首次登录，已自动注册: userId={}", maskPhone(phone), user.getId());
        }
        assertNotFrozen(user);
        // 弱口令缓冲期：与用户名密码登录同一口径（新注册账号口令是随机的，不会被拦）
        assertLoginAllowedForWeakPassword(user);
        UserLoginVO vo = buildLoginVO(user);
        BizTrace.result(BIZ_TYPE_LOGIN, phone, true,
                "登录成功 userId=" + user.getId() + " 需改密=" + vo.getNeedChangePassword());
        BizTrace.cost(BIZ_TYPE_LOGIN, phone, traceStart);
        return vo;
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
        // 【注意】只校验 pay_password 列，禁止改读 password：两列一旦互相读写，
        // 用户改登录口令就会顺带改变支付能力，且把登录口令牵进支付链路
        String payPasswordHash = baseMapper.selectPayPassword(userId);
        if (payPasswordHash == null || !passwordEncoder.matches(pw, payPasswordHash)) {
            // 不回显任何默认口令：错误提示里带口令等于把攻击所需的最后一个信息直接送给调用方
            throw new BizIllegalException("支付密码错误");
        }
        // 【首次支付前强制改密】仍在使用初始支付口令时拦截本次扣款，引导用户先改密。
        //   · 判据是"当前口令是否等于初始口令"，不是"是否改过" —— 用户把口令改成其它值后即永久放行，
        //     即使后来又把口令改回初始值也无所谓（那已是用户的自主选择，不再是"从未设置过"）；
        //   · 放在密码校验之后：先证明"你知道当前口令"，避免把"没设过口令"这一状态
        //     泄露给不知道口令的调用方（否则等于给攻击者一个免费的状态探测接口）；
        //   · 已自行设置过支付密码的账号完全无感，支付主流程不变。
        if (passwordEncoder.matches(INITIAL_PASSWORD, payPasswordHash)) {
            log.warn("拦截余额支付：账号仍在使用初始支付口令，需先修改支付密码: userId={}, bizNo={}",
                    userId, bizNo);
            throw new BizIllegalException("请先修改支付密码（当前仍为初始密码），修改后再进行余额支付");
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

        // ② 条件更新（balance >= amount 作为 SQL 的 WHERE 条件），按受影响行数判断：余额不足时不会扣成负数
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
        vo.setNeedChangePayPassword(isUsingDefaultPayPassword(user.getId()));
        vo.setNeedChangeLoginPassword(isUsingDefaultLoginPassword(user));
        // 汇总标记：任一口令仍是初始值即需要改密（前端据此在进入业务页面前引导，两个细分字段用于给出精确文案）
        vo.setNeedChangePassword(Boolean.TRUE.equals(vo.getNeedChangePayPassword())
                || Boolean.TRUE.equals(vo.getNeedChangeLoginPassword()));
        return vo;
    }

    /**
     * 登录阶段的弱口令校验：只有登录口令仍等于初始口令时才进入缓冲期判定。
     *
     * 【弱口令分层拦截边界】收口由三层构成，各自拦的东西不同：
     * 1. 登录响应标记（needChangePassword）：只提示、不拦截，拦不住"前端不配合"的客户端；
     * 2. 登录硬拦截（本方法链，缓冲期外）：服务端强制，拦"用初始口令继续登录"；
     * 3. 支付入口拦截（deductMoney）：拦"用初始支付口令动用余额"。
     *
     * 【兜底不可省的是第 2 层】它是唯一既不依赖前端配合、也不依赖资金动作的关卡：
     * 少了它，存量弱口令账号可以永远用初始口令登录，"强制改密"就只剩一句提示。
     *
     * 【只拦登录口令】支付口令的拦截点放在余额支付入口：在那里拒绝更精确（只挡资金动作），
     * 不影响用户浏览与下单；若支付口令也拦登录，用户会因"没设置过支付口令"而连商品都看不了。
     *
     * 【边界约束】判据只有"登录口令是否等于初始口令"这一条：新口令本身只受长度约束
     * （见 LoginPasswordFormDTO），服务端不查弱口令字典，也不比对新口令与用户名、手机号的相似度。
     */
    private void assertLoginAllowedForWeakPassword(User user) {
        if (isUsingDefaultLoginPassword(user)) {
            assertWeakPasswordWithinGrace(user.getId());
        }
    }

    /**
     * 弱口令缓冲期判定：期内放行（仅提示），到期后硬拦截登录。
     *
     * 【为什么在登录阶段硬拦截而不是只靠前端标记引导】
     * 只返回标记的话，"改不改"完全取决于前端是否配合；存量弱口令账号的接管风险是真实存在的
     * （用户名由手机号推导 + 口令固定为初始值），必须有一条服务端强制的收口路径。
     *
     * 【起点写入时机】首次检测到弱口令时写入当前时间，之后不再刷新 ——
     * 否则用户每次登录都会把到期时间往后推，缓冲期变成永久。
     *
     * 【失败处理】Redis 异常时放行（不阻断登录）：
     * 缓冲期是温和收口手段，不能因为缓存抖动把正常用户全挡在门外；
     * 真正的强约束（支付入口拦截）不依赖 Redis，仍然生效。
     */
    private void assertWeakPasswordWithinGrace(Long userId) {
        String key = AuthConstants.REDIS_WEAK_PASSWORD_SINCE + userId;
        try {
            String since = redisTemplate.opsForValue().get(key);
            if (since == null) {
                // 首次检测：记录缓冲期起点（不续期，见方法注释）
                redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                        WEAK_PASSWORD_GRACE);
                return;
            }
            long sinceMillis = Long.parseLong(since);
            if (System.currentTimeMillis() - sinceMillis > WEAK_PASSWORD_GRACE.toMillis()) {
                log.warn("弱口令缓冲期已过，拦截登录并要求改密: userId={}", userId);
                throw new BizIllegalException("您的登录密码仍为初始密码且已超过 "
                        + WEAK_PASSWORD_GRACE.toDays() + " 天，为保障账号安全请先修改登录密码后再登录");
            }
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            // 起点读取/写入失败：放行并告警，不把缓存故障升级成"用户无法登录"
            log.warn("弱口令缓冲期校验失败（放行，不阻断登录）: userId={}", userId, e);
        }
    }

    /**
     * 清除弱口令缓冲期标记。口令修改成功后必须调用，
     * 否则"已改密"的账号还会在下次登录时被判为弱口令（缓冲期起点仍在）。
     */
    private void clearWeakPasswordGrace(Long userId) {
        try {
            redisTemplate.delete(AuthConstants.REDIS_WEAK_PASSWORD_SINCE + userId);
        } catch (Exception e) {
            log.warn("清除弱口令缓冲期标记失败（下次登录会重新记录起点，影响有限）: userId={}", userId, e);
        }
    }

    // 【口令防暴力破解】以下三个方法只服务于改密接口，执行顺序即：频控 → 锁定检查 → 失败计数。

    /**
     * 口令修改接口的滑动窗口频控（同一账号 1 小时最多 5 次）。
     *
     * 【为什么改密接口也要限流】改密接口同时校验"原密码"与"验证码"，
     * 不限流就等于给攻击者一个可以反复试探原密码的入口（配合验证码爆破放大危害）。
     *
     * 【实现取舍】用固定窗口计数器而非滑动窗口：够用且只需一次 INCR + 一次 EXPIRE，
     * 代价是窗口边界处最坏可通过 2 倍次数（10 次/小时），对预防暴力破解足够。
     */
    private void assertPasswordChangeAllowed(Long userId) {
        String key = AuthConstants.REDIS_PWD_CHANGE_LIMIT + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, PWD_CHANGE_WINDOW);
            }
            if (count != null && count > PWD_CHANGE_MAX_PER_HOUR) {
                log.warn("口令修改请求过于频繁，已拒绝: userId={}, count={}", userId, count);
                throw new BizIllegalException("操作过于频繁，请稍后再试");
            }
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            // 频控依赖 Redis：不可用时放行（保可用）但告警，避免把缓存故障变成"无法改密"
            log.warn("口令修改频控校验失败（放行）: userId={}", userId, e);
        }
    }

    /** 口令错误锁定校验：锁定期内直接拒绝，连密码比对都不做（避免用比对耗时做侧信道探测） */
    private void assertNotPasswordLocked(Long userId) {
        String lockKey = AuthConstants.REDIS_PWD_LOCK + userId;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
                Long ttl = redisTemplate.getExpire(lockKey);
                long minutes = ttl == null || ttl < 0 ? PWD_LOCK_DURATION.toMinutes() : (ttl + 59) / 60;
                log.warn("账号处于口令锁定状态，拒绝验证: userId={}, 剩余约 {} 分钟", userId, minutes);
                throw new BizIllegalException("支付密码连续输错次数过多，账号已临时锁定，请 "
                        + minutes + " 分钟后再试");
            }
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            log.warn("口令锁定状态查询失败（放行）: userId={}", userId, e);
        }
    }

    /**
     * 记录一次口令错误：达到阈值即锁定。
     *
     * 【计数与锁定分开两个 key 的原因】计数需要"锁定解除后归零"，
     * 若与锁定共用一个 key，锁定到期时计数也跟着消失是恰好想要的；
     * 但二者 TTL 不同（计数略长于锁定），分开写更直观且便于运维单查锁定状态。
     */
    private void recordPasswordFailure(Long userId) {
        String failKey = AuthConstants.REDIS_PWD_FAIL_COUNT + userId;
        try {
            Long fails = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, PWD_FAIL_TTL);
            if (fails != null && fails >= PWD_MAX_FAIL) {
                redisTemplate.opsForValue().set(AuthConstants.REDIS_PWD_LOCK + userId, "1", PWD_LOCK_DURATION);
                redisTemplate.delete(failKey);
                log.error("【安全】支付密码连续错误 {} 次，账号已锁定 {} 分钟: userId={}",
                        PWD_MAX_FAIL, PWD_LOCK_DURATION.toMinutes(), userId);
            } else if (fails != null) {
                log.warn("支付密码错误（第 {}/{} 次）: userId={}", fails, PWD_MAX_FAIL, userId);
            }
        } catch (Exception e) {
            log.warn("记录口令错误次数失败: userId={}", userId, e);
        }
    }

    /**
     * 查询支付密码锁定状态。
     *
     * 【实现说明】直接查锁定 key 的剩余 TTL，而不是自己维护"锁定到什么时候"：
     * key 的 TTL 就是锁定的权威剩余时间，自己再存一份时间戳会引入第二个真相源。
     *
     * 【异常处理】Redis 不可用时返回"未锁定"而不是报错：
     * 这是展示态接口，把它变成 500 会让支付页整页报错；
     * 真到了校验环节，{@link #assertNotPasswordLocked} 会按自己的判据处理（找不到 key 即放行），
     * 两者对"Redis 不可用"的取向一致 —— 保可用。
     */
    @Override
    public PwdLockStatusVO queryPayPasswordLockStatus() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        PwdLockStatusVO vo = new PwdLockStatusVO().setLocked(false).setRemainMinutes(0L);
        try {
            Long ttlSeconds = redisTemplate.getExpire(AuthConstants.REDIS_PWD_LOCK + userId);
            if (ttlSeconds != null && ttlSeconds > 0) {
                // 向上取整：剩余 10 秒若显示 0 分钟，用户会立刻重试并再次失败
                long minutes = Math.max(1L, (ttlSeconds + 59) / 60);
                vo.setLocked(true).setRemainMinutes(minutes);
            }
        } catch (Exception e) {
            log.warn("查询支付密码锁定状态失败（按未锁定返回，不影响页面渲染）: userId={}", userId, e);
        }
        return vo;
    }

    /** 口令校验通过后清零失败计数与锁定标记 */
    private void clearPasswordFailure(Long userId) {        try {
            redisTemplate.delete(AuthConstants.REDIS_PWD_FAIL_COUNT + userId);
            redisTemplate.delete(AuthConstants.REDIS_PWD_LOCK + userId);
        } catch (Exception e) {
            log.warn("清除口令错误计数失败: userId={}", userId, e);
        }
    }

    /**
     * 判断该账号是否仍在使用初始登录口令（即存量弱口令账号）。
     *
     * 【为什么用"比对初始口令"而不是新增标记列】
     * 标记列会引入"写口令却忘了改标记"的双写一致性问题；比对哈希直接反映当前事实，无需 DDL。
     *
     * 【代价】每次组装用户视图多一次 bcrypt 校验。仅登录与"获取当前用户"两条低频路径会走到这里；
     * 若将来该字段出现在高频路径上，应改为维护 need_change 标记列。
     *
     * 【注意】新注册账号的登录口令是随机值（不可预期、不返回给调用方），不会命中本判据 ——
     * 本判据只服务于口令仍等于初始口令的存量账号。
     */
    private boolean isUsingDefaultLoginPassword(User user) {
        try {
            String hash = user.getPassword();
            return hash != null && passwordEncoder.matches(INITIAL_PASSWORD, hash);
        } catch (Exception e) {
            // 判据取不到时不阻断登录：返回 false（不误报"需要改密"），只留日志
            log.warn("判断是否使用初始登录口令失败（按 false 处理，不阻断登录）: userId={}", user.getId(), e);
            return false;
        }
    }

    /**
     * 判断该账号是否仍在使用初始支付口令，供调用方在首次支付前强制引导改密。
     *
     * 【为什么用"比对哈希"而不是新增标记列】比对哈希不需要 DDL 变更，也不会因为
     * "标记列忘了同步"而失真：它就是支付校验所依据的那一列的当前事实。
     *
     * 【代价与取舍】每次组装用户视图多一次 bcrypt 校验。仅登录与"获取当前用户"
     * 两条低频路径会走到这里，可以接受；若将来该字段出现在高频路径上，应改为在
     * pay_password 上维护一个 need_change 标记列。
     *
     * 【安全】只返回布尔值，不返回任何口令信息。
     */
    private boolean isUsingDefaultPayPassword(Long userId) {
        try {
            String hash = baseMapper.selectPayPassword(userId);
            // 支付密码为空同样视为"未设置"，需要引导
            return hash == null || passwordEncoder.matches(INITIAL_PASSWORD, hash);
        } catch (Exception e) {
            // 判据取不到时不阻断登录：返回 false（不误报"需要改密"），只留日志
            log.warn("判断是否使用初始支付口令失败（按 false 处理，不阻断登录）: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 生成自动注册账号的随机登录口令。
     *
     * 【注意】必须是随机值且绝不返回给调用方：自动注册的用户名由手机号派生（u_{手机号}）是公开可推导的，
     * 登录口令一旦固定（例如与初始口令相同），任何人只要知道手机号就能直接用户名密码登录、绕过短信验证码接管账号。
     * 随机口令使"用户名可推导"这一事实不再构成风险：口令不可预期，账号只能经短信验证码进入。
     */
    private String randomLoginPassword() {
        return RandomUtil.randomString(24);
    }

    /**
     * 原子核销手机验证码（登录与修改支付密码共用）。
     *
     * 【并发设计】核销必须一次原子完成"读值 + 比对 + 删除"（见 CONSUME_CODE_SCRIPT）：
     * 拆成 GET → 比对 → DEL 三步时，并发请求会各自读到值并都通过比对，同一验证码等于可重放。
     *
     * 【异常口径】区分四种结果而不是笼统报"验证码错误"：不存在/已过期、填错、被并发抢先核销
     * 对应的用户动作不同（重新获取 / 重新输入 / 直接重试），混成一句会让用户不知道该做什么。
     *
     * 【fail-closed】Redis 不可用时拒绝而不是跳过校验：这是认证链路，放行等于取消验证码。
     *
     * @param phone     手机号（已验证格式）
     * @param submitted 用户提交的验证码
     * @throws BizIllegalException 验证码为空/过期/错误/已被核销/校验失败
     */
    private void consumeLoginCode(String phone, String submitted) {
        if (StrUtil.isBlank(submitted)) {
            throw new BizIllegalException("验证码不能为空");
        }
        String codeKey = AuthConstants.REDIS_LOGIN_CODE + phone;
        Long outcome;
        try {
            outcome = redisTemplate.execute(CONSUME_CODE_SCRIPT,
                    Collections.singletonList(codeKey), submitted);
        } catch (Exception e) {
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
    }

    /**
     * 修改/设置支付密码：频控 → 锁定检查 → 验证码 → 原支付口令 → 新旧不同 → 落库 → 会话失效。
     *
     * 【为什么短信验证码与原口令都要】两者防的是不同攻击面，不能互相替代：
     * 原口令防"会话令牌被盗后静默改密"，验证码防"账号被完全接管后连支付口令一起改掉"。
     *
     * 【为什么新口令不能与原口令相同】若允许相同，"强制改密"会被用户原样再存一次而形同虚设，
     * 下一次支付仍会被拦截，体验更差且风险未收口。
     *
     * 【改密校验成本排序：先廉价后昂贵，顺序不得调整】
     * 1. 频控最先（见 PWD_CHANGE_* 常量）：只做一次 Redis 自增，成本最低，
     *    而它挡的正是"反复试探原口令"这个入口；
     * 2. 锁定检查第二：同样只读一个 key，挡"绕过频控后的无限次尝试"；
     * 3. 验证码第三：核销成本高于前两者，但它要求攻击者持有绑定手机号，先把没有手机号的人挡在外面；
     * 4. 原口令比对最后：bcrypt 比对是整条链路里最昂贵的一步，必须排在所有廉价关卡之后。
     * 【顺序反了的后果】把 bcrypt 比对提前，等于让未通过频控与验证码的请求也能触发哈希计算：
     * 撞库与爆破的每个请求都会消耗一次 CPU 密集型比对，既放大攻击面又浪费资源。
     *
     * 【改密后的会话处理】改密即等于全部设备下线：调用
     * {@link ITokenService#invalidateAllSessions} 作废该用户全部 refresh 并写入失效水位，
     * 使此前签发的 access 立即失效；否则攻击者被挤出去后，仍可在旧 access 的剩余寿命内操作账户。
     *
     * 【注意】只更新 pay_password 一列，绝不触碰 password（登录口令）：
     * 两列职责独立，混写会让"改支付密码"顺带改变登录能力。
     *
     * @throws UnauthorizedException 未登录（无 user-info 上下文）
     * @throws BizIllegalException   请求过于频繁/账号锁定/验证码错误/原支付口令错误/新旧口令相同
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePayPassword(PayPasswordFormDTO form) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        User user = getById(userId);
        if (user == null) {
            throw new BizIllegalException("用户不存在");
        }
        // ⓪ 防暴力破解：频控 + 锁定（在任何口令比对之前）
        assertPasswordChangeAllowed(userId);
        assertNotPasswordLocked(userId);

        // ① 短信验证码：证明操作者持有该账号绑定的手机号（复用登录验证码，原子核销）
        consumeLoginCode(user.getPhone(), form.getCode());

        // ② 原支付密码：只比对 pay_password 列，与登录口令严格分离
        String currentHash = baseMapper.selectPayPassword(userId);
        if (currentHash == null || !passwordEncoder.matches(form.getOldPassword(), currentHash)) {
            // 记录失败次数（达阈值即锁定）。注意：验证码已在 ① 被核销，
            // 用户需要重新获取验证码才能再试 —— 这本身就是一道额外的成本。
            recordPasswordFailure(userId);
            // 不回显任何默认口令：错误提示里带口令等于把攻击所需的最后一个信息送给调用方
            throw new BizIllegalException("原支付密码错误");
        }

        // ③ 新旧必须不同：否则"改密"是空操作，强制改密会被绕过
        if (passwordEncoder.matches(form.getNewPassword(), currentHash)) {
            throw new BizIllegalException("新支付密码不能与原支付密码相同");
        }

        lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPayPassword, passwordEncoder.encode(form.getNewPassword()))
                .update();
        // ④ 改密成功：清零失败计数 + 强制全部会话下线（口令是最高凭证，改密即等于全设备登出）
        clearPasswordFailure(userId);
        // 会话失效放在事务提交之后：本方法带 @Transactional，若提交阶段失败（口令实际未变），
        // 提前踢人会让用户在什么都没改成的情况下被登出，且旧口令仍有效 —— 语义矛盾。
        invalidateSessionsAfterCommit(userId);
        log.info("支付密码已更新，该用户全部会话将在事务提交后失效: userId={}", userId);
    }

    /**
     * 事务提交后再执行"全量会话失效"。
     *
     * 【为什么必须等提交】改密与踢人是因果绑定的一对：
     * 口令真的改了才应该踢人。若在事务内就踢，一旦提交失败（口令未变、事务回滚），
     * 用户会在"什么都没改成"的情况下被登出，而旧口令依然可用 —— 既伤害体验又没有安全收益。
     *
     * 【为什么用 TxAfterCommit 而不是 TransactionSynchronization 手写】
     * 项目已有该工具（llmall-common），且它统一处理了"无事务上下文时降级为立即执行"，
     * 并保证回调不抛异常（提交已成功，不能让 Redis 抖动冒泡成"改密失败"）。
     *
     * 【代价】提交成功、回调执行前进程崩溃 → 本次不踢人：该用户的旧 refresh 与旧 access
     * 在各自剩余寿命内仍可用，要到下次改密或令牌自然过期才被纠正。
     */
    private void invalidateSessionsAfterCommit(Long userId) {
        TxAfterCommit.run("invalidate sessions after password change user=" + userId,
                () -> tokenService.invalidateAllSessions(userId));
    }

    /**
     * 修改登录密码（需登录）。
     *
     * 【与 {@link #changePayPassword} 的关系】两者共用同一套防护（频控、锁定、验证码、
     * 会话失效），差别只在"改哪一列"与"原始口令取哪列"：
     * 本方法动 {@code password}，支付密码方法动 {@code pay_password}，二者互不干扰。
     *
     * 【为什么登录口令也要提供修改入口】弱口令收口策略里，
     * 存量账号的登录口令仍是固定值；没有这条通道，"强制引导改密"就只能引导不能落地。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeLoginPassword(LoginPasswordFormDTO form) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }
        User user = getById(userId);
        if (user == null) {
            throw new BizIllegalException("用户不存在");
        }
        // ⓪ 防暴力破解：频控 + 锁定
        assertPasswordChangeAllowed(userId);
        assertNotPasswordLocked(userId);

        // ① 短信验证码：证明持有账号绑定的手机号
        consumeLoginCode(user.getPhone(), form.getCode());

        // ② 原登录口令：只比对 password 列
        if (user.getPassword() == null || !passwordEncoder.matches(form.getOldPassword(), user.getPassword())) {
            recordPasswordFailure(userId);
            throw new BizIllegalException("原登录密码错误");
        }

        // ③ 新旧必须不同
        if (passwordEncoder.matches(form.getNewPassword(), user.getPassword())) {
            throw new BizIllegalException("新登录密码不能与原登录密码相同");
        }

        lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getPassword, passwordEncoder.encode(form.getNewPassword()))
                .update();
        // ④ 改密成功：清零失败计数、清除弱口令缓冲期标记、全部会话下线
        clearPasswordFailure(userId);
        clearWeakPasswordGrace(userId);
        // 同 changePayPassword：会话失效等提交后再执行，避免"提交失败却已踢人"
        invalidateSessionsAfterCommit(userId);
        log.info("登录密码已更新，该用户全部会话将在事务提交后失效: userId={}", userId);
    }

    /** 脱敏格式：138****1234 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
