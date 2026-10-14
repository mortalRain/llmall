package com.llmall.promotion.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.client.TradeClient;
import com.llmall.api.client.UserClient;
import com.llmall.api.dto.MoneyDeductDTO;
import com.llmall.api.dto.TradeOrderDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.utils.UserContext;
import com.llmall.promotion.config.RabbitConfig;
import com.llmall.promotion.constants.PromotionConstants;
import com.llmall.promotion.constants.PromotionRedisScripts;
import com.llmall.promotion.domain.po.ConsumeRecord;
import com.llmall.promotion.domain.po.SeckillVoucher;
import com.llmall.promotion.domain.po.UserVoucher;
import com.llmall.promotion.domain.po.Voucher;
import com.llmall.promotion.domain.vo.SeckillResultVO;
import com.llmall.promotion.domain.vo.UserVoucherVO;
import com.llmall.promotion.domain.vo.VoucherVO;
import com.llmall.promotion.mapper.ConsumeRecordMapper;
import com.llmall.promotion.mapper.SeckillVoucherMapper;
import com.llmall.promotion.mapper.UserVoucherMapper;
import com.llmall.promotion.mapper.VoucherMapper;
import com.llmall.promotion.mq.SeckillMessage;
import com.llmall.promotion.service.IVoucherService;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 券服务实现：普通领券 + 秒杀抢券 + 券核销/退回。
 * 并发正确性靠三道闸（Redis 已购集合 → user_voucher 唯一键 → consume_record 幂等）；库存以 DB 为最终账本，Redis 只做秒杀预扣。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    /** Redis 库存键前缀：值为剩余可抢数 */
    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    /** Redis 已购集合键前缀：成员为 userId，用于一人一单的第一道拦截 */
    private static final String ORDERED_KEY_PREFIX = "seckill:ordered:";
    /**
     * Redis 已发放计数键前缀：值为该券累计放出过的名额。
     *
     * 【注意】它与库存键在同一 Lua 脚本内同增同减，{@code 库存 + 已发放} 恒等于该券总名额，
     * 是库存键丢失后唯一的重建依据；在途未落库的预扣不可能从 DB 查出来。
     */
    private static final String ISSUED_KEY_PREFIX = "seckill:issued:";
    /** Redis 秒杀结果键前缀：值为 SeckillResultVO 的 JSON，供客户端轮询 */
    private static final String RESULT_KEY_PREFIX = "seckill:result:";

    /** Lua 脚本返回值：预扣成功 */
    private static final long LUA_SUCCESS = 0L;
    /** Lua 脚本返回值：库存不足 */
    private static final long LUA_SOLD_OUT = 1L;
    /** Lua 脚本返回值：该用户已抢购过 */
    private static final long LUA_DUPLICATE = 2L;

    /**
     * 回补预扣的脚本：库存 +1、已发放 -1、按需把用户放回已购集合，三者一次原子完成。
     *
     * 【注意】库存键可能在回补瞬间刚好过期，若先 {@code hasKey} 判断再 {@code INCR}，
     * INCR 会凭空造出一个值为 1 的库存键，"键已过期即秒杀结束"的保护随之失效；
     * 返回 0 即整体空操作（不造库存），返回 1 表示确实回补了库存。
     *
     * KEYS[1]=库存键，KEYS[2]=已发放键，KEYS[3]=已购集合键；ARGV[1]=userId，
     * ARGV[2]="1" 表示这次抢购确实放弃了，要把购买资格一起还回去。
     */
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 0 then return 0 end "
                    + "redis.call('incr', KEYS[1]) "
                    + "if redis.call('exists', KEYS[2]) == 1 then redis.call('decr', KEYS[2]) end "
                    + "if ARGV[2] == '1' then redis.call('srem', KEYS[3], ARGV[1]) end "
                    + "return 1",
            Long.class);

    /** 回补脚本 ARGV[2]：是否把用户放回已购集合 */
    private static final String RELEASE_USER = "1";
    private static final String KEEP_USER = "0";

    /**
     * 秒杀缓存的引导脚本：只负责建立库存键与已发放键，不碰已购集合、不消耗名额。
     *
     * 【注意】不能复用 seckill.lua 引导：它在引导后会继续执行正常抢购流程，
     * 给探针 userId 扣掉一个库存并把它 SADD 进已购集合，等于每次预热白送一张券，
     * 且集合里会留下没有券行对应的幽灵成员。本脚本"键不存在才建立"的判断条件
     * 与 seckill.lua 的引导分支一致（同一套防超卖夹取），谁先执行结果都相同。
     *
     * KEYS[1]=库存键，KEYS[2]=已发放键；ARGV[1]=安全的引导库存，ARGV[2]=TTL 秒数。
     * 返回 1 表示这次真的做了引导，0 表示键已存在、空操作。
     */
    private static final DefaultRedisScript<Long> BOOTSTRAP_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "local initStock = tonumber(ARGV[1]) "
                    + "if not initStock or initStock < 0 then initStock = 0 end "
                    + "local issued = tonumber(redis.call('get', KEYS[2])) or 0 "
                    + "if issued < 0 then issued = 0 end "
                    + "local bootstrapStock = initStock - issued "
                    + "if bootstrapStock < 0 then bootstrapStock = 0 end "
                    + "redis.call('set', KEYS[1], bootstrapStock) "
                    + "redis.call('set', KEYS[2], initStock - bootstrapStock) "
                    + "redis.call('expire', KEYS[1], ARGV[2]) "
                    + "redis.call('expire', KEYS[2], ARGV[2]) "
                    + "return 1",
            Long.class);

    private final DefaultRedisScript<Long> seckillScript;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final UserVoucherMapper userVoucherMapper;
    private final ConsumeRecordMapper consumeRecordMapper;
    private final UserClient userClient;
    private final TradeClient tradeClient;

    /** 交易订单状态：1 未付款 2 已付款（与 llmall-trade-service 的 Order 常量保持一致） */
    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_PAID = 2;
    /** 5 已取消/已关闭（订单取消或已退款的终态） */
    private static final int ORDER_STATUS_CLOSED = 5;
    /** 7 已核销：券一旦随订单核销，就绝不允许再退 */
    private static final int ORDER_STATUS_VERIFIED = 7;

    /** 秒杀结果标记保留时长（分钟） */
    @Value("${llmall.promotion.seckill-result-ttl-minutes:10}")
    private long resultTtlMinutes;

    /** 券规则里没写"有效天数"时的默认有效期（天） */
    @Value("${llmall.promotion.default-valid-days:30}")
    private int defaultValidDays;


    @Override
    public List<VoucherVO> listAvailable(Long shopId, Integer type) {
        List<Voucher> vouchers = lambdaQuery()
                .eq(Voucher::getStatus, Voucher.STATUS_ON)
                .eq(shopId != null, Voucher::getShopId, shopId)
                .eq(type != null, Voucher::getType, type)
                .orderByAsc(Voucher::getId)
                .list();
        if (CollUtils.isEmpty(vouchers)) {
            return CollUtils.emptyList();
        }

        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).collect(Collectors.toList());
        Map<Long, SeckillVoucher> configMap = querySeckillConfigs(voucherIds);
        Map<Long, Integer> redisStock = readRedisStock(new ArrayList<>(configMap.keySet()));
        Set<Long> receivedIds = queryReceivedVoucherIds(voucherIds);

        List<VoucherVO> result = new ArrayList<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            result.add(toVO(voucher, configMap.get(voucher.getId()),
                    receivedIds.contains(voucher.getId()), redisStock));
        }
        return result;
    }

    @Override
    public VoucherVO getVoucherDetail(Long voucherId) {
        Voucher voucher = baseMapper.selectById(voucherId);
        if (voucher == null) {
            throw new BizIllegalException("优惠券不存在");
        }
        SeckillVoucher config = seckillVoucherMapper.selectById(voucherId);
        Map<Long, Integer> redisStock = config == null
                ? CollUtils.emptyMap()
                : readRedisStock(CollUtils.singletonList(voucherId));
        boolean received = !queryReceivedVoucherIds(CollUtils.singletonList(voucherId)).isEmpty();
        return toVO(voucher, config, received, redisStock);
    }

    @Override
    public List<UserVoucherVO> listMyVouchers(Integer status) {
        Long userId = requireLogin();
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<UserVoucher> wrapper = Wrappers.<UserVoucher>lambdaQuery()
                .eq(UserVoucher::getUserId, userId);
        if (status == null) {
            // 不传状态：返回该用户全部券（含过期），由前端按 status 分 Tab
        } else if (status == UserVoucher.STATUS_EXPIRED) {
            // 已过期要含"库里是 1、但 expire_time 已过"的券，否则两个 Tab 对不上
            wrapper.and(w -> w.eq(UserVoucher::getStatus, UserVoucher.STATUS_EXPIRED)
                    .or(inner -> inner.eq(UserVoucher::getStatus, UserVoucher.STATUS_UNUSED)
                            .isNotNull(UserVoucher::getExpireTime)
                            .lt(UserVoucher::getExpireTime, now)));
        } else if (status == UserVoucher.STATUS_UNUSED) {
            // 未使用反向排除已过期的，保证两个 Tab 互斥不重复
            wrapper.eq(UserVoucher::getStatus, UserVoucher.STATUS_UNUSED)
                    .and(w -> w.isNull(UserVoucher::getExpireTime)
                            .or().gt(UserVoucher::getExpireTime, now));
        } else {
            wrapper.eq(UserVoucher::getStatus, status);
        }
        wrapper.orderByDesc(UserVoucher::getId);

        List<UserVoucher> list = userVoucherMapper.selectList(wrapper);
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }

        List<Long> voucherIds = list.stream().map(UserVoucher::getVoucherId).distinct().collect(Collectors.toList());
        Map<Long, Voucher> voucherMap = baseMapper.selectBatchIds(voucherIds).stream()
                .collect(Collectors.toMap(Voucher::getId, v -> v, (a, b) -> a));

        List<UserVoucherVO> result = new ArrayList<>(list.size());
        for (UserVoucher userVoucher : list) {
            Voucher voucher = voucherMap.get(userVoucher.getVoucherId());
            result.add(toUserVoucherVO(userVoucher, voucher, now));
        }
        return result;
    }

    /**
     * 我的券详情（券实例 id）：归属失败抛 {@link ForbiddenException}；过期按 expire_time 惰性判定，不做写回。
     */
    @Override
    public UserVoucherVO getMyVoucher(Long userVoucherId) {
        Long userId = requireLogin();
        UserVoucher userVoucher = userVoucherMapper.selectById(userVoucherId);
        if (userVoucher == null || !Objects.equals(userVoucher.getUserId(), userId)) {
            throw new ForbiddenException("券不存在或不属于当前用户");
        }
        return toUserVoucherVO(userVoucher, baseMapper.selectById(userVoucher.getVoucherId()), LocalDateTime.now());
    }

    /** 券实例 + 券模板 → VO；列表与详情共用，避免两处过期判定出现偏差 */
    private UserVoucherVO toUserVoucherVO(UserVoucher userVoucher, Voucher voucher, LocalDateTime now) {
        return new UserVoucherVO()
                .setId(userVoucher.getId())
                .setVoucherId(userVoucher.getVoucherId())
                .setTitle(voucher == null ? null : voucher.getTitle())
                .setSubTitle(voucher == null ? null : voucher.getSubTitle())
                .setRules(voucher == null ? null : voucher.getRules())
                .setActualValue(voucher == null ? null : voucher.getActualValue())
                .setShopId(userVoucher.getShopId())
                .setOrderId(userVoucher.getOrderId())
                // 过期状态惰性判定：不依赖定时任务刷 status，查询瞬间用 expire_time 与当前时间比较
                .setStatus(resolveStatus(userVoucher, now))
                .setReceiveTime(userVoucher.getReceiveTime())
                .setUseTime(userVoucher.getUseTime())
                .setExpireTime(userVoucher.getExpireTime());
    }


    /** 免费券领取入口（向后兼容）；付费券必须提供支付密码，见 {@link #receive(Long, String)} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long receive(Long voucherId) {
        return receive(voucherId, null);
    }

    /**
     * 领取普通券：先收款，再扣库存、发券；券款走 Feign 不在本地事务内，发券失败必须显式退款补偿。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long receive(Long voucherId, String pw) {
        Long userId = requireLogin();
        Voucher voucher = baseMapper.selectById(voucherId);
        if (voucher == null) {
            throw new BizIllegalException("优惠券不存在");
        }
        if (!Objects.equals(voucher.getStatus(), Voucher.STATUS_ON)) {
            throw new BizIllegalException("优惠券已下架，无法领取");
        }

        // 一人一张预检必须在扣款之前，否则已领过的用户会先被扣钱再报错；真正的保证是 user_voucher 唯一键
        // MyBatis-Plus 3.5.x 起 selectCount 返回 Long（3.4.x 为 Integer）
        Long count = userVoucherMapper.selectCount(Wrappers.<UserVoucher>lambdaQuery()
                .eq(UserVoucher::getUserId, userId)
                .eq(UserVoucher::getVoucherId, voucherId));
        if (count != null && count > 0) {
            throw new BizIllegalException("您已领取过该券，请勿重复领取");
        }

        // 先收款：免费券 pay_value=0 在此直接跳过
        //    幂等键按"一人一张券"取 —— uk_user_voucher 已保证一个用户对一张券只能买一次，
        //    于是同一业务号的重复到达（重复点击、网络重试）不会重复扣款
        String purchaseBizNo = "VOUCHER-" + voucherId + "-" + userId;
        boolean paid = chargeVoucherPurchase(voucher, userId, pw, purchaseBizNo);

        try {
            // 只有配了秒杀库存行的券才有"库存"概念，rows=0 即已领完；这里不扣 Redis，DB 才是最终闸门
            SeckillVoucher config = seckillVoucherMapper.selectById(voucherId);
            if (config != null) {
                int rows = seckillVoucherMapper.deductStock(voucherId);
                if (rows == 0) {
                    throw new BizIllegalException("该券已被领完");
                }
            }

            LocalDateTime now = LocalDateTime.now();
            UserVoucher userVoucher = new UserVoucher()
                    .setUserId(userId)
                    .setVoucherId(voucherId)
                    .setShopId(voucher.getShopId())
                    .setStatus(UserVoucher.STATUS_UNUSED)
                    .setReceiveTime(now)
                    .setExpireTime(calcExpireTime(voucher, now));
            try {
                userVoucherMapper.insert(userVoucher);
            } catch (DuplicateKeyException e) {
                // 并发重复领取被唯一键拦下；抛异常回滚整个事务，上面扣掉的库存一并还回
                throw new BizIllegalException("您已领取过该券，请勿重复领取", e);
            }
            log.info("领券成功: userId={}, voucherId={}, userVoucherId={}, 售价={}分",
                    userId, voucherId, userVoucher.getId(), payValueOf(voucher));
            return userVoucher.getId();
        } catch (RuntimeException e) {
            // 失败补偿：券已被事务回滚，已收的券款必须原路退回
            //    退款幂等键与扣款一一对应（VOUCHER_REFUND-...），重复补偿只退一次
            refundPurchaseQuietly(paid, voucher, userId, "领券失败补偿", "VOUCHER_REFUND-" + voucherId + "-" + userId);
            throw e;
        }
    }


    /** 免费券秒杀入口（向后兼容）：付费秒杀券必须带支付密码，见 {@link #seckill(Long, String)} */
    @Override
    public String seckill(Long voucherId) {
        return seckill(voucherId, null);
    }

    /**
     * 秒杀抢券：先收款，再 Lua 原子预扣名额，最后投递 MQ 异步落库；返回 token 供客户端轮询。
     *
     * 【注意】付费券先收款再占名额，热路径因此多一次同步扣款；预扣成功之后的每一步失败都必须退款并回补名额。
     */
    @Override
    public String seckill(Long voucherId, String pw) {
        Long userId = requireLogin();
        Voucher voucher = baseMapper.selectById(voucherId);
        if (voucher == null) {
            throw new BizIllegalException("优惠券不存在");
        }
        if (!Objects.equals(voucher.getStatus(), Voucher.STATUS_ON)) {
            throw new BizIllegalException("优惠券已下架，无法抢购");
        }
        SeckillVoucher config = seckillVoucherMapper.selectById(voucherId);
        if (config == null) {
            throw new BizIllegalException("该优惠券不是秒杀券");
        }
        // 时段校验放在最前面：未开始/已结束的流量直接返回，省掉一次 Redis 往返
        LocalDateTime now = LocalDateTime.now();
        if (config.getBeginTime() != null && now.isBefore(config.getBeginTime())) {
            throw new BizIllegalException("秒杀尚未开始");
        }
        if (config.getEndTime() != null && now.isAfter(config.getEndTime())) {
            throw new BizIllegalException("秒杀已结束");
        }

        // 缓存预热：Redis 丢数据后库存键与已购集合都要重建 —— 只恢复库存会让抢过的用户重新通过第一道闸
        warmUpSeckillCache(voucherId, config);

        // 先收款：钱不到位就不去占稀缺名额 —— 扣款是最易失败的跨服务动作，放到 Lua 之后要靠补偿还名额
        //    token 在收款之前生成：它既是抢购凭证，也是这一次收款的幂等键（同一 token 重复到达不会重复扣款）
        String token = IdUtil.fastSimpleUUID();
        String purchaseBizNo = "VOUCHER-SECKILL-" + token;
        //    退款幂等键也用 token：与 MQ 消费端落库失败后的退款（refundSeckillPurchase）是同一个键，
        //    避免"投递已成功但响应丢失"时两边各退一次造成双倍退款
        String refundBizNo = "SECKILL_REFUND:" + token;
        boolean paid = chargeVoucherPurchase(voucher, userId, pw, purchaseBizNo);

        // 先写 PENDING 结果标记、再投递消息：顺序反了，消费者的 SUCCESS 会被随后的 PENDING 盖掉
        writeSeckillResult(SeckillResultVO.pending(token, voucherId, "抢购请求已受理，正在生成券"));

        // Lua 原子预扣：库存判断 + 一人一单 + 扣减 + 记名一次完成，失败按返回码分流并退款
        Long code = executeSeckillScript(voucherId, config, userId);
        if (code == null) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "系统繁忙，抢购失败"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀预扣失败补偿", refundBizNo);
            throw new BizIllegalException("系统繁忙，请稍后重试");
        }
        if (code == LUA_DUPLICATE) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "您已抢购过该券"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀重复抢购补偿", refundBizNo);
            throw new BizIllegalException("您已抢购过该券，请勿重复抢购");
        }
        if (code == LUA_SOLD_OUT) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "该券已抢完"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀已抢完补偿", refundBizNo);
            throw new BizIllegalException("手慢了，该券已被抢完");
        }
        if (code != LUA_SUCCESS) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "抢购失败"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀预扣失败补偿", refundBizNo);
            throw new BizIllegalException("抢购失败，请稍后重试");
        }

        // 投递 MQ 异步落库；消息体显式带 userId —— MQ 消费线程没有请求上下文，UserContext 一定是空的
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.PROMOTION_EXCHANGE, RabbitConfig.SECKILL_ORDER_KEY,
                    new SeckillMessage().setToken(token).setUserId(userId).setVoucherId(voucherId));
        } catch (Exception e) {
            // 发不出去等于预扣白扣：回补 Redis 名额 + 退回券款 + 标记失败，否则名额占死且无记录可查
            log.error("秒杀消息投递失败，回补 Redis 预扣: voucherId={}, userId={}", voucherId, userId, e);
            compensateSeckillDeduction(voucherId, userId);
            refundPurchaseQuietly(paid, voucher, userId, "秒杀消息投递失败补偿", refundBizNo);
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "系统繁忙，抢购失败，请稍后重试"));
            throw new BizIllegalException("系统繁忙，抢购失败，请稍后重试");
        }

        log.info("秒杀预扣成功并已投递异步落库: userId={}, voucherId={}, token={}, 售价={}分",
                userId, voucherId, token, payValueOf(voucher));
        return token;
    }

    /**
     * 秒杀落库失败后的购券退款：以 consume_record(biz_type=SECKILL_REFUND, biz_id=token) 占位为幂等键，占位与退款同事务，重复投递直接跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refundSeckillPurchase(String token, Long voucherId, Long userId, String scene) {
        if (StrUtil.isBlank(token) || voucherId == null || userId == null) {
            return false;
        }
        Voucher voucher = baseMapper.selectById(voucherId);
        long payValue = payValueOf(voucher);
        if (payValue <= 0) {
            return false;
        }
        try {
            consumeRecordMapper.insert(new ConsumeRecord()
                    .setBizType(PromotionConstants.BIZ_SECKILL_REFUND)
                    .setBizId(token)
                    .setConsumer(PromotionConstants.CONSUMER_NAME));
        } catch (DuplicateKeyException e) {
            log.info("该秒杀购买款已退款过，幂等跳过: token={}", token);
            return false;
        }
        // 幂等键必须与秒杀收款路径用的退款键一致（SECKILL_REFUND:<token>），否则两条路径各退一次 → 双倍退款
        userClient.refundMoney(userId, (int) payValue, "SECKILL_REFUND:" + token);
        log.warn("{}：秒杀购券款已退回: token={}, userId={}, voucherId={}, amount={}分",
                scene, token, userId, voucherId, payValue);
        return true;
    }

    @Override
    public SeckillResultVO querySeckillResult(Long voucherId, String token) {
        if (StrUtil.isBlank(token)) {
            throw new BadRequestException("秒杀凭证不能为空");
        }
        String json = redisTemplate.opsForValue().get(RESULT_KEY_PREFIX + token);
        if (StrUtil.isBlank(json)) {
            // 凭证不存在或已过期：不谎报成功，也不谎报失败，让客户端以"我的券"为准
            return SeckillResultVO.pending(token, voucherId, "结果尚未返回或凭证已过期，请到「我的券」确认最终结果");
        }
        SeckillResultVO result = JSONUtil.toBean(json, SeckillResultVO.class);
        if (result.getVoucherId() != null && voucherId != null && !result.getVoucherId().equals(voucherId)) {
            throw new BadRequestException("秒杀凭证与券不匹配");
        }
        return result;
    }


    /**
     * 秒杀落库：幂等占位 + 发券 + 扣 DB 库存，三步必须同一事务，否则首次失败留下的占位会把 MQ 重投挡成"已消费"。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long handleSeckillOrder(SeckillMessage message) {
        if (message == null || StrUtil.isBlank(message.getToken())
                || message.getUserId() == null || message.getVoucherId() == null) {
            log.warn("收到非法的秒杀消息，忽略: {}", message);
            return null;
        }
        String token = message.getToken();

        // 消息级幂等占位：uk_consume(biz_type, biz_id, consumer) 直接裁决，不做"先查再插"
        try {
            consumeRecordMapper.insert(new ConsumeRecord()
                    .setBizType(PromotionConstants.BIZ_SECKILL_ORDER)
                    .setBizId(token)
                    .setConsumer(PromotionConstants.CONSUMER_NAME));
        } catch (DuplicateKeyException e) {
            log.info("该秒杀消息已消费过，幂等跳过: token={}", token);
            return null;
        }

        Voucher voucher = baseMapper.selectById(message.getVoucherId());
        if (voucher == null) {
            throw new BizIllegalException("优惠券不存在，无法发券");
        }

        LocalDateTime now = LocalDateTime.now();
        // 发券：uk_user_voucher 是"一人一张"的数据库兜底闸门；放在扣库存之前，撞唯一键立刻失败且回滚后库存未动
        UserVoucher userVoucher = new UserVoucher()
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId())
                .setShopId(voucher.getShopId())
                .setStatus(UserVoucher.STATUS_UNUSED)
                .setReceiveTime(now)
                .setExpireTime(calcExpireTime(voucher, now));
        try {
            userVoucherMapper.insert(userVoucher);
        } catch (DuplicateKeyException e) {
            // 该用户已通过别的入口持有该券：重试永远不成功，只还库存名额，不清理 Redis 已购集合（他确实持有）
            compensateRedisDeduction(message.getVoucherId(), message.getUserId(), false);
            throw new BizIllegalException("您已持有该券，不能重复抢购", e);
        }

        // Redis 只是预扣，DB 才是最终账本，这一步保证"缓存与 DB 失配"时不会真的超发
        int rows = seckillVoucherMapper.deductStock(message.getVoucherId());
        if (rows == 0) {
            compensateRedisDeduction(message.getVoucherId(), message.getUserId(), true);
            throw new BizIllegalException("手慢了，该券已被抢完");
        }

        log.info("秒杀落库成功: token={}, userId={}, voucherId={}, userVoucherId={}",
                token, message.getUserId(), message.getVoucherId(), userVoucher.getId());
        return userVoucher.getId();
    }

    @Override
    public void markSeckillSuccess(String token, Long voucherId, Long userVoucherId) {
        if (StrUtil.isBlank(token)) {
            return;
        }
        writeSeckillResult(SeckillResultVO.success(token, voucherId, userVoucherId));
    }

    @Override
    public void markSeckillFailed(String token, Long voucherId, String message) {
        if (StrUtil.isBlank(token)) {
            return;
        }
        writeSeckillResult(SeckillResultVO.failed(token, voucherId, message));
    }

    @Override
    public void compensateSeckillDeduction(Long voucherId, Long userId) {
        compensateRedisDeduction(voucherId, userId, true);
    }


    /**
     * 核销（锁定）券：状态 1 → 2 并绑定订单号；只能由订单链路调用，硬证据是 order.voucher_id 必须等于本券 id。
     */
    @Override
    public boolean useMyVoucher(Long userVoucherId, Long orderId) {
        Long userId = requireLogin();
        UserVoucher userVoucher = userVoucherMapper.selectById(userVoucherId);
        // 归属校验放在最前面：券 id 是可枚举的自增值，不校验归属就等于"知道 id 就能核销别人的券"
        if (userVoucher == null || !Objects.equals(userVoucher.getUserId(), userId)) {
            throw new ForbiddenException("券不存在或不属于当前用户");
        }
        // 订单号必传：核销永远是"某笔订单用掉了这张券"的结果，没有订单就不该有核销
        if (orderId == null) {
            throw new BadRequestException("核销必须由订单链路发起：缺少订单号");
        }
        TradeOrderDTO order = queryOrderForVoucher(orderId, userId, userVoucher);

        LocalDateTime now = LocalDateTime.now();
        if (userVoucher.getStatus() == UserVoucher.STATUS_UNUSED
                && userVoucher.getExpireTime() != null && userVoucher.getExpireTime().isBefore(now)) {
            throw new BizIllegalException("券已过期，无法核销");
        }
        if (userVoucher.getStatus() == UserVoucher.STATUS_USED) {
            // 幂等：同一订单重复提交视为"已核销成功"；能走到这里说明订单已通过上面的回查与绑定校验
            if (Objects.equals(userVoucher.getOrderId(), orderId)) {
                log.info("券已核销，幂等返回成功: userVoucherId={}, orderId={}", userVoucherId, orderId);
                return true;
            }
            throw new BizIllegalException("券已被其它订单使用，不能重复核销");
        }
        if (userVoucher.getStatus() != UserVoucher.STATUS_UNUSED) {
            throw new BizIllegalException("券当前状态不允许核销");
        }

        // 交叉校验：订单上的 voucher_id 必须就是这张券 —— 状态与门店都可能"碰巧对上"，这个字段才是硬证据
        if (!Objects.equals(order.getVoucherId(), userVoucherId)) {
            log.warn("券与订单未绑定，拒绝核销: userVoucherId={}, orderId={}, orderVoucherId={}",
                    userVoucherId, orderId, order.getVoucherId());
            throw new ForbiddenException("该券未用于此订单，不能核销");
        }
        // 状态白名单：只有 已支付(2) 或 待支付但已锁定该券(1) 才允许核销；已取消(5)/已核销(7) 都不允许
        Integer status = order.getStatus();
        boolean paid = status != null && status == ORDER_STATUS_PAID;
        boolean lockedPending = status != null && status == ORDER_STATUS_UNPAID;
        if (!paid && !lockedPending) {
            throw new BizIllegalException("订单状态不允许核销该券（当前状态 " + status + "）");
        }

        // 条件更新核销：WHERE status = 1 保证并发下只有一个请求真正改成 2，影响 0 行就再读一次给准确提示
        LambdaUpdateWrapper<UserVoucher> update = Wrappers.<UserVoucher>lambdaUpdate()
                .set(UserVoucher::getStatus, UserVoucher.STATUS_USED)
                .set(UserVoucher::getUseTime, now)
                .eq(UserVoucher::getId, userVoucherId)
                .eq(UserVoucher::getUserId, userId)
                .eq(UserVoucher::getStatus, UserVoucher.STATUS_UNUSED);
        update.set(UserVoucher::getOrderId, orderId);
        int rows = userVoucherMapper.update(null, update);
        if (rows == 0) {
            UserVoucher latest = userVoucherMapper.selectById(userVoucherId);
            if (latest != null && latest.getStatus() == UserVoucher.STATUS_USED
                    && Objects.equals(latest.getOrderId(), orderId)) {
                return true;
            }
            throw new BizIllegalException("券状态已变化，核销失败");
        }
        log.info("券核销成功: userVoucherId={}, userId={}, orderId={}", userVoucherId, userId, orderId);
        return true;
    }

    /**
     * 退回券（状态 2 → 1，幂等）：只允许订单取消/退款链路调用，orderId 必传并回查订单；订单已核销（status=7）时绝不允许退券。
     */
    @Override
    public boolean refundMyVoucher(Long userVoucherId, Long orderId) {
        Long userId = requireLogin();
        UserVoucher userVoucher = userVoucherMapper.selectById(userVoucherId);
        if (userVoucher == null || !Objects.equals(userVoucher.getUserId(), userId)) {
            throw new ForbiddenException("券不存在或不属于当前用户");
        }
        if (orderId == null) {
            throw new BadRequestException("退券必须由订单链路发起：缺少订单号");
        }
        TradeOrderDTO order = queryOrderForVoucher(orderId, userId, userVoucher);
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_VERIFIED) {
            throw new BizIllegalException("订单已核销，券不能退回");
        }
        // 幂等：已经是"未使用"说明上一次退回已经生效（或这张券本来就没用过），直接返回成功
        if (userVoucher.getStatus() == UserVoucher.STATUS_UNUSED) {
            log.info("券已处于未使用状态，幂等返回成功: userVoucherId={}", userVoucherId);
            return true;
        }
        if (userVoucher.getStatus() == UserVoucher.STATUS_EXPIRED) {
            throw new BizIllegalException("券已过期，无法退回");
        }
        // 订单一致性校验：防止 A 订单取消时把 B 订单用掉的券退回（交易域按 orderId 调用）
        if (userVoucher.getOrderId() != null && !Objects.equals(userVoucher.getOrderId(), orderId)) {
            log.warn("券与订单不匹配，拒绝退回: userVoucherId={}, 券绑定订单={}, 传入订单={}",
                    userVoucherId, userVoucher.getOrderId(), orderId);
            throw new BizIllegalException("券与订单不匹配，无法退回");
        }

        // 条件更新 2 → 1：同样用 WHERE status = 2 兜住并发重复调用
        int rows = userVoucherMapper.update(null, Wrappers.<UserVoucher>lambdaUpdate()
                .set(UserVoucher::getStatus, UserVoucher.STATUS_UNUSED)
                .eq(UserVoucher::getId, userVoucherId)
                .eq(UserVoucher::getUserId, userId)
                .eq(UserVoucher::getStatus, UserVoucher.STATUS_USED));
        if (rows == 0) {
            UserVoucher latest = userVoucherMapper.selectById(userVoucherId);
            if (latest != null && latest.getStatus() == UserVoucher.STATUS_UNUSED) {
                return true;
            }
            throw new BizIllegalException("券状态已变化，退回失败");
        }
        // use_time / order_id 保留不动：它们是"这张券曾被哪笔订单用过"的审计线索，且表为 not_null 更新策略
        log.info("券退回成功: userVoucherId={}, userId={}, orderId={}", userVoucherId, userId, orderId);
        return true;
    }

    /**
     * 回查订单并做归属 / 门店校验：orderId 是不可信入参，券与订单分属两个库，本域必须自己查证。
     */
    private TradeOrderDTO queryOrderForVoucher(Long orderId, Long userId, UserVoucher userVoucher) {
        TradeOrderDTO order;
        try {
            order = tradeClient.queryOrderById(orderId);
        } catch (FeignException e) {
            // 交易域用非 2xx 业务错误体回话，Feign 因此抛异常：403=订单存在但不属于你（越权要留痕），其余按"不存在/参数问题"处理
            if (e.status() == 403) {
                log.warn("越权尝试：券被用于他人订单: orderId={}, userVoucherId={}, userId={}",
                        orderId, userVoucher.getId(), userId);
                throw new ForbiddenException("券不能用于他人订单");
            }
            log.warn("回查交易订单失败，拒绝操作券: orderId={}, userVoucherId={}", orderId, userVoucher.getId(), e);
            throw new BadRequestException("订单不存在或不可用，无法操作该优惠券");
        } catch (Exception e) {
            log.warn("回查交易订单异常，拒绝操作券: orderId={}, userVoucherId={}", orderId, userVoucher.getId(), e);
            throw new BadRequestException("订单不存在或不可用，无法操作该优惠券");
        }
        if (order == null || order.getId() == null) {
            throw new BadRequestException("订单不存在，无法操作该优惠券");
        }
        // 归属再显式断言一次：跨服务调用的信任边界要写出来，不能默认上游已经校验过
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new ForbiddenException("券不能用于他人订单");
        }
        // 门店校验：券是分门店发放与结算的（券 shopId 为空表示全平台通用券）
        if (userVoucher.getShopId() != null && order.getShopId() != null
                && !Objects.equals(userVoucher.getShopId(), order.getShopId())) {
            log.warn("券与订单门店不一致: userVoucherId={}, 券门店={}, 订单门店={}",
                    userVoucher.getId(), userVoucher.getShopId(), order.getShopId());
            throw new BizIllegalException("券与订单门店不一致，无法操作该券");
        }
        return order;
    }


    /** 券售价（分）；空值按 0 处理（免费券） */
    private long payValueOf(Voucher voucher) {
        if (voucher == null || voucher.getPayValue() == null || voucher.getPayValue() <= 0) {
            return 0L;
        }
        return voucher.getPayValue();
    }

    /**
     * 付费券收款：pay_value = 0 的免费券直接返回 false；付费券走 llmall-user-service 带支付密码校验的扣款接口，不新增内部免密通道。
     *
     * @param bizNo 幂等键（调用方给出，须唯一标识"这一次购买"）：普通领券用 {@code VOUCHER-<券id>-<用户id>}，
     *              秒杀用 {@code VOUCHER-SECKILL-<token>}；下游按它落资金流水并去重，重复到达不重复扣款。
     */
    private boolean chargeVoucherPurchase(Voucher voucher, Long userId, String pw, String bizNo) {
        long payValue = payValueOf(voucher);
        if (payValue <= 0) {
            return false;
        }
        if (StrUtil.isBlank(pw)) {
            throw new BadRequestException("该券为付费券（" + (payValue / 100.0) + " 元），请提交支付密码");
        }
        try {
            // 支付密码走请求体而非 URL（query 会被 access log 与代理原样记录）；bizNo 用 VOUCHER 前缀，便于对账区分买券与付订单
            userClient.deductMoney(new MoneyDeductDTO()
                    .setPw(pw)
                    .setAmount((int) payValue)
                    .setBizNo(bizNo));
        } catch (Exception e) {
            // 扣款失败必须在发券之前结束：把上游 msg 原样带出（密码错/余额不足对用户有用），不回显内部堆栈
            String reason = extractUpstreamMsg(e);
            log.warn("付费券扣款失败: userId={}, voucherId={}, payValue={}, reason={}",
                    userId, voucher.getId(), payValue, reason);
            throw new BizIllegalException("券售价支付失败：" + reason);
        }
        log.info("付费券收款成功: userId={}, voucherId={}, payValue={}分", userId, voucher.getId(), payValue);
        return true;
    }

    /** 取下游统一响应体里的 msg 给前端展示：只抛"调用失败"的话，用户分不清是密码错还是余额不足 */
    private String extractUpstreamMsg(Exception e) {
        try {
            if (e instanceof FeignException) {
                String body = ((FeignException) e).contentUTF8();
                if (StrUtil.isNotBlank(body) && JSONUtil.isTypeJSONObject(body)) {
                    String msg = JSONUtil.parseObj(body).getStr("msg");
                    if (StrUtil.isNotBlank(msg)) {
                        return msg;
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "请检查支付密码与账户余额";
    }

    /**
     * 券款退回补偿：失败只记 ERROR —— 留有可检索日志供人工对账，但不因退款接口抖动吞掉主流程异常。
     *
     * @param refundBizNo 退款幂等键，必须与这一笔收款一一对应（与 MQ 消费端的落库失败退款共用
     *                    {@code SECKILL_REFUND:<token>}），保证重复补偿只退一次钱
     */
    private void refundPurchaseQuietly(boolean paid, Voucher voucher, Long userId, String scene, String refundBizNo) {
        if (!paid) {
            return;
        }
        long payValue = payValueOf(voucher);
        try {
            userClient.refundMoney(userId, (int) payValue, refundBizNo);
            log.warn("{}：券款已退回: userId={}, voucherId={}, amount={}分, bizNo={}", scene, userId, voucher.getId(), payValue, refundBizNo);
        } catch (Exception e) {
            log.error("{}：券款退回失败，需人工补偿: userId={}, voucherId={}, amount={}分, bizNo={}",
                    scene, userId, voucher.getId(), payValue, refundBizNo, e);
        }
    }


    /** 当前登录用户：身份由网关校验 JWT 后写入 user-info 头，本服务只读 ThreadLocal，不解析 token */
    private Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }

    /** 执行秒杀 Lua 脚本，返回脚本的返回码；Redis 异常返回 null（调用方按"系统繁忙"处理） */
    private Long executeSeckillScript(Long voucherId, SeckillVoucher config, Long userId) {
        try {
            return redisTemplate.execute(seckillScript,
                    Arrays.asList(stockKey(voucherId), orderedKey(voucherId), issuedKey(voucherId)),
                    String.valueOf(userId),
                    // 引导库存必须取"安全值"（已扣掉 DB 已发放的部分），不能直接用 config.stock，
                    // 否则 Redis 丢数据后会把已经卖出去的名额重新放出来 → 超卖（见 warmUpSeckillCache）
                    String.valueOf(safeBootstrapStock(voucherId, config)),
                    String.valueOf(cacheTtlSeconds(config)));
        } catch (Exception e) {
            log.error("执行秒杀 Lua 脚本失败: voucherId={}, userId={}", voucherId, userId, e);
            return null;
        }
    }

    /**
     * 预热秒杀缓存（库存 + 已购集合 + 已发放计数），两个键各自判断、缺哪个补哪个。
     *
     * 【注意】只判断库存键是否存在会漏掉"库存键在、已购集合丢"的情况，抢过的用户会重新
     * 通过第一道闸、白跑一趟 DB；判据用 {@code COUNT}（走索引）而非每次预热拉全量
     * user_voucher 行，避免把全表级扫描放进每个秒杀请求的路径。
     */
    private void warmUpSeckillCache(Long voucherId, SeckillVoucher config) {
        String stockKey = stockKey(voucherId);
        String orderedKey = orderedKey(voucherId);
        String issuedKey = issuedKey(voucherId);
        try {
            boolean stockExists = Boolean.TRUE.equals(redisTemplate.hasKey(stockKey));
            Long orderedSize = redisTemplate.opsForSet().size(orderedKey);
            boolean orderedExists = orderedSize != null && orderedSize > 0;
            if (stockExists && orderedExists) {
                return;
            }

            if (!stockExists) {
                Long safeStock = safeBootstrapStock(voucherId, config);
                Long bootstrapped = redisTemplate.execute(BOOTSTRAP_SCRIPT,
                        Arrays.asList(stockKey, issuedKey),
                        String.valueOf(safeStock),
                        String.valueOf(cacheTtlSeconds(config)));
                log.warn("检测到秒杀库存键缺失（Redis 丢数据），已按安全值引导: voucherId={}, config.stock={}, "
                                + "safeStock={}, 是否引导={}",
                        voucherId, config.getStock(), safeStock, bootstrapped);
            }

            if (!orderedExists) {
                Long total = userVoucherMapper.selectCount(Wrappers.<UserVoucher>lambdaQuery()
                        .eq(UserVoucher::getVoucherId, voucherId));
                long held = total == null ? 0L : total;
                if (held > 0) {
                    List<UserVoucher> rows = userVoucherMapper.selectList(Wrappers.<UserVoucher>lambdaQuery()
                            .select(UserVoucher::getUserId)
                            .eq(UserVoucher::getVoucherId, voucherId));
                    String[] userIds = rows.stream().map(v -> String.valueOf(v.getUserId()))
                            .distinct().toArray(String[]::new);
                    if (userIds.length > 0) {
                        // 已购集合是一人一单的第一道闸，必须整份写入：部分写入不报错，
                        // 只表现为老用户重新通过 Redis 判定、白跑 DB 与数据库压力升高；
                        // 且 add 与 expire 分成两条命令会留下没有 TTL 的集合（现役 Redis 是
                        // noeviction，不会自己回收），故用一条脚本连 TTL 一起设好。
                        String[] args = new String[userIds.length + 1];
                        args[0] = String.valueOf(cacheTtlSeconds(config));
                        System.arraycopy(userIds, 0, args, 1, userIds.length);
                        redisTemplate.execute(PromotionRedisScripts.SET_REBUILD_WITH_TTL,
                                Collections.singletonList(orderedKey), (Object[]) args);
                    }
                    log.warn("秒杀已购集合缺失，已按 user_voucher 重建: voucherId={}, heldUsers={}",
                            voucherId, userIds.length);
                }
            }
        } catch (Exception e) {
            // 预热失败不阻断主流程：Lua 还有"用安全初值兜底初始化"一层，最坏是已购集合缺失（由唯一键兜住）
            log.warn("秒杀缓存预热失败，将依赖 Lua 兜底初始化: voucherId={}", voucherId, e);
        }
    }

    /**
     * 计算安全的引导库存：{@code config.stock - 该券已发放的 DB 行数}，下限 0。
     *
     * 【注意】不能直接用 {@code config.stock}：它是权威扣减后的剩余量，但没算上 MQ 里
     * 尚未落库的预扣，直接当可抢库存会让这些在途预扣再扣一次 DB 库存 → 超卖。
     * 故以"总共发放过多少"反推，DB 已发放行数是唯一可信来源；代价是在途预扣不计入
     * 已发放，重建后可能少卖 —— 刻意偏向少卖而非超卖。
     */
    private long safeBootstrapStock(Long voucherId, SeckillVoucher config) {
        long stock = config.getStock() == null ? 0L : config.getStock();
        try {
            Long issued = userVoucherMapper.selectCount(Wrappers.<UserVoucher>lambdaQuery()
                    .eq(UserVoucher::getVoucherId, voucherId));
            long issuedCount = issued == null ? 0L : issued;
            long safe = stock - issuedCount;
            return Math.max(safe, 0L);
        } catch (Exception e) {
            // 查不出来就按 0 处理（fail-closed）：没有依据时宁可少卖，绝不凭 DB 剩余量放行
            log.error("统计券已发放数量失败，引导库存按 0 处理（宁可少卖不超卖）: voucherId={}", voucherId, e);
            return 0L;
        }
    }

    /**
     * 回补 Redis 预扣：库存 +1、已发放 -1，并按需把用户从已购集合里移除。
     * @param releaseUserFlag true=这次抢购放弃了，库存与购买资格都还；false=用户确实已持有该券，只还库存
     */
    private void compensateRedisDeduction(Long voucherId, Long userId, boolean releaseUserFlag) {
        try {
            Long compensated = redisTemplate.execute(COMPENSATE_SCRIPT,
                    Arrays.asList(stockKey(voucherId), issuedKey(voucherId), orderedKey(voucherId)),
                    userId == null ? "0" : String.valueOf(userId),
                    releaseUserFlag ? RELEASE_USER : KEEP_USER);
            if (compensated == null || compensated == 0L) {
                // 库存键已过期（秒杀结束）：整体空操作，不造库存
                log.warn("库存键已过期，跳过回补（不造库存）: voucherId={}, userId={}", voucherId, userId);
                return;
            }
            log.warn("已回补 Redis 秒杀预扣: voucherId={}, userId={}, releaseUser={}",
                    voucherId, userId, releaseUserFlag);
        } catch (Exception e) {
            // 回补失败会让 Redis 名额与 DB 不一致（表现为少卖而非超卖，可接受），但必须留日志对账
            log.error("回补 Redis 秒杀预扣失败，需要人工/对账处理: voucherId={}, userId={}", voucherId, userId, e);
        }
    }

    /** 写秒杀结果标记，供客户端轮询 /vouchers/seckill/{id}/result */
    private void writeSeckillResult(SeckillResultVO result) {
        try {
            redisTemplate.opsForValue().set(RESULT_KEY_PREFIX + result.getToken(),
                    JSONUtil.toJsonStr(result), Duration.ofMinutes(resultTtlMinutes));
        } catch (Exception e) {
            // 标记写失败不影响落库结果本身：用户查"我的券"依然能看到券
            log.error("写入秒杀结果标记失败: token={}", result.getToken(), e);
        }
    }

    /** 券过期时间 = 当前时间 + 规则里的"有效天数"（缺失或解析失败回落默认天数）；领取时算好落库，高频读不必反复解析 JSON */
    private LocalDateTime calcExpireTime(Voucher voucher, LocalDateTime now) {
        int days = defaultValidDays;
        String rules = voucher.getRules();
        if (StrUtil.isNotBlank(rules)) {
            try {
                JSONObject json = JSONUtil.parseObj(rules);
                // 券规则的有效天数键为中文"有效天数"，同时兼容英文键 validDays
                Integer configured = json.getInt("有效天数", json.getInt("validDays", null));
                if (configured != null && configured > 0) {
                    days = configured;
                }
            } catch (Exception e) {
                log.warn("券规则 JSON 解析失败，使用默认有效期 {} 天: voucherId={}, rules={}",
                        defaultValidDays, voucher.getId(), rules);
            }
        }
        return now.plusDays(days);
    }

    /** 过期状态惰性判定：库里是 1（未使用）且 expire_time 已过 → 对外呈现为 3（已过期） */
    private int resolveStatus(UserVoucher userVoucher, LocalDateTime now) {
        Integer status = userVoucher.getStatus();
        if (status != null && status == UserVoucher.STATUS_UNUSED
                && userVoucher.getExpireTime() != null && userVoucher.getExpireTime().isBefore(now)) {
            return UserVoucher.STATUS_EXPIRED;
        }
        return status == null ? UserVoucher.STATUS_UNUSED : status;
    }

    private Map<Long, SeckillVoucher> querySeckillConfigs(List<Long> voucherIds) {
        if (CollUtils.isEmpty(voucherIds)) {
            return CollUtils.emptyMap();
        }
        return seckillVoucherMapper.selectBatchIds(voucherIds).stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, c -> c, (a, b) -> a));
    }

    /** 查当前登录用户已领取的券 id 集合；未登录返回空集合（公开列表里 received 恒为 false） */
    private Set<Long> queryReceivedVoucherIds(List<Long> voucherIds) {
        Long userId = UserContext.getUser();
        if (userId == null || CollUtils.isEmpty(voucherIds)) {
            return CollUtils.emptySet();
        }
        return userVoucherMapper.selectList(Wrappers.<UserVoucher>lambdaQuery()
                        .select(UserVoucher::getVoucherId)
                        .eq(UserVoucher::getUserId, userId)
                        .in(UserVoucher::getVoucherId, voucherIds))
                .stream().map(UserVoucher::getVoucherId).collect(Collectors.toSet());
    }

    /** 读 Redis 实时库存：一次 MGET 取完；Redis 不可用时降级返回空 Map，由调用方回落 DB 库存 */
    private Map<Long, Integer> readRedisStock(List<Long> voucherIds) {
        if (CollUtils.isEmpty(voucherIds)) {
            return CollUtils.emptyMap();
        }
        try {
            List<String> keys = voucherIds.stream().map(id -> STOCK_KEY_PREFIX + id).collect(Collectors.toList());
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            Map<Long, Integer> stockMap = new HashMap<>(voucherIds.size());
            if (values != null) {
                for (int i = 0; i < values.size() && i < voucherIds.size(); i++) {
                    String value = values.get(i);
                    if (StrUtil.isNotBlank(value)) {
                        stockMap.put(voucherIds.get(i), Integer.valueOf(value));
                    }
                }
            }
            return stockMap;
        } catch (Exception e) {
            log.warn("读取 Redis 秒杀库存失败，本次降级为 DB 库存", e);
            return CollUtils.emptyMap();
        }
    }

    private VoucherVO toVO(Voucher voucher, SeckillVoucher config, boolean received, Map<Long, Integer> redisStock) {
        VoucherVO vo = new VoucherVO()
                .setId(voucher.getId())
                .setShopId(voucher.getShopId())
                .setTitle(voucher.getTitle())
                .setSubTitle(voucher.getSubTitle())
                .setRules(voucher.getRules())
                .setPayValue(voucher.getPayValue())
                .setActualValue(voucher.getActualValue())
                .setType(voucher.getType())
                .setStatus(voucher.getStatus())
                .setReceived(received);
        if (config != null) {
            Integer redisValue = redisStock.get(voucher.getId());
            vo.setBeginTime(config.getBeginTime())
                    .setEndTime(config.getEndTime())
                    // 优先展示 Redis 预扣后的实时库存（秒杀进行中它才是真实可抢数），键不存在时回落 DB
                    .setRemainingStock(redisValue != null ? redisValue : config.getStock())
                    .setInSeckillTime(inSeckillTime(config));
        }
        return vo;
    }

    /** 是否处于秒杀时段：不限时段（begin/end 为空）视为始终可抢 */
    private boolean inSeckillTime(SeckillVoucher config) {
        LocalDateTime now = LocalDateTime.now();
        if (config.getBeginTime() != null && now.isBefore(config.getBeginTime())) {
            return false;
        }
        return config.getEndTime() == null || !now.isAfter(config.getEndTime());
    }

    /** 缓存键过期时间：秒杀窗口内绝不允许过期（过期会把库存重置回 DB 值），窗口结束后再多留 1 天便于排查 */
    private long cacheTtlSeconds(SeckillVoucher config) {
        long oneDay = Duration.ofDays(1).getSeconds();
        if (config.getEndTime() == null) {
            return Duration.ofDays(7).getSeconds();
        }
        long seconds = Duration.between(LocalDateTime.now(), config.getEndTime()).getSeconds() + oneDay;
        return Math.max(seconds, Duration.ofHours(1).getSeconds());
    }

    private String stockKey(Long voucherId) {
        return STOCK_KEY_PREFIX + voucherId;
    }

    private String orderedKey(Long voucherId) {
        return ORDERED_KEY_PREFIX + voucherId;
    }

    private String issuedKey(Long voucherId) {
        return ISSUED_KEY_PREFIX + voucherId;
    }
}
