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
import com.llmall.common.constant.IdempotentKeys;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.common.trace.BizTrace;
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
 * 券业务的唯一实现：普通领券与付费券收款、秒杀抢券（Lua 原子预扣 + MQ 异步落库）、我的券查询与核销/退回。
 *
 * 【核心职责】
 * 1. 普通领券：持久化购买请求、事务外收款、同事务发券和成功状态，异常由原子撤销与恢复任务处理；
 * 2. 秒杀抢券：库存引导 → 付费券收款 → Lua 原子预扣 → 投递 MQ 异步落库，返回 token 供客户端轮询；
 * 3. 券的核销（1 → 2）与退回（2 → 1）：只接受订单链路调用，orderId 必传并回查交易订单。
 *
 * 【设计意图】并发正确性靠三道闸：Redis 已购集合（挡并发洪峰）→ user_voucher 唯一键（一人一张）→ consume_record 幂等（消息级去重）；
 * 库存以 DB 为最终账本，Redis 只做秒杀预扣。库存的「引导」与「扣减」是两条独立路径，见 warmUpSeckillStock 与 executeSeckillScript 的注释。
 *
 * 【注意】秒杀券的库存口径是「总名额」而不是「剩余量」，Java 侧算错会让剩余库存永久少卖且无法自愈，见 totalSeckillStock 的注释。
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

    /** 四段式埋点的业务类型标识（见 llmall-common 的 BizTrace） */
    private static final String BIZ_TYPE_SECKILL = "SECKILL";

    // 普通领券由purchaseCoordinator管理持久化尝试；秒杀仍按token关联旧收退款。

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
     * 【为什么不能用 seckill.lua 代做引导】它在引导之后会继续执行正常抢购流程：
     * 用传入的 userId 扣掉一个库存并把它写进已购集合，等于每次预热都白送一张券，
     * 且集合里会留下没有券行对应的幽灵成员。本脚本与 seckill.lua 的引导分支是同一套夹取规则，谁先执行结果都相同。
     *
     * 【参数口径】
     * KEYS[1]=库存键 seckill:stock:{voucherId}（值为剩余可抢数），KEYS[2]=已发放键 seckill:issued:{voucherId}；
     * ARGV[1]=该券总名额 T（不是剩余量），由 totalSeckillStock 算出 = DB 剩余量 + DB 已发放行数；
     * ARGV[2]=TTL 秒数；ARGV[3]=已知已放出数量（DB 已发放行数，作为 Redis 丢数据时的保守下限）。
     *
     * 【误传后果】ARGV[1] 传成「剩余量」会让库存被二次扣减后夹到 0：DB 里还剩的券全部不可售，
     * 且重新预热走的是同一个 totalSeckillStock，永远无法自愈。
     *
     * 【返回】1=本次确实做了引导（库存键原本不存在）；0=库存键已在，整体空操作，不写任何 key。
     *
     * 【多副本同步】本脚本与 src/main/resources/lua/seckill.lua 的引导分支是同一套夹取规则的两个副本，改一处必须同步另一处。
     */
    private static final DefaultRedisScript<Long> BOOTSTRAP_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "local total = tonumber(ARGV[1]) "
                    + "if not total or total < 0 then total = 0 end "
                    + "local knownIssued = tonumber(ARGV[3]) or 0 "
                    + "if knownIssued < 0 then knownIssued = 0 end "
                    + "local redisIssued = tonumber(redis.call('get', KEYS[2])) or 0 "
                    + "if redisIssued < 0 then redisIssued = 0 end "
                    // 取两者较大值：Redis 认识的已发放可能比 DB 更多（存在尚未落库的在途预扣），
                    // 取大值即把在途预扣也算作"已放出"，宁可少卖也不超卖
                    + "local issued = redisIssued "
                    + "if knownIssued > issued then issued = knownIssued end "
                    + "local bootstrapStock = total - issued "
                    + "if bootstrapStock < 0 then bootstrapStock = 0 end "
                    + "redis.call('set', KEYS[1], bootstrapStock) "
                    + "redis.call('set', KEYS[2], issued) "
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
    private final VoucherBindingStore voucherBindingStore;
    private final BizMetrics metrics;
    private final VoucherPurchaseCoordinator purchaseCoordinator;

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


    /** Free legacy entry; paid ordinary purchases use the requestId overload. */
    @Override
    public Long receive(Long voucherId) {
        return receive(voucherId, null, null);
    }

    @Override
    public Long receive(Long voucherId, String pw) {
        return receive(voucherId, pw, null);
    }

    @Override
    public Long receive(Long voucherId, String pw, String requestId) {
        return purchaseCoordinator.receive(voucherId, pw, requestId);
    }

    @Override
    public com.llmall.promotion.domain.vo.VoucherPurchaseVO purchase(String requestId) {
        return purchaseCoordinator.status(requestId);
    }

    /** 免费秒杀兼容入口；付费新请求由 {@link #seckill(Long, String)} 统一拒绝。 */
    @Override
    public String seckill(Long voucherId) {
        return seckill(voucherId, null);
    }

    /**
     * 免费秒杀演示：时段检查 → Redis 名额预扣 → MQ 异步发券，返回 token 供轮询。
     * 付费新请求在任何预热/扣款前拒绝；旧请求的消费与补偿入口保留用于恢复。
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
        // 旧秒杀没有持久化购买恢复协议；只保留免费演示，付费走普通购买入口。
        if (payValueOf(voucher) > 0) {
            throw new BadRequestException("暂不支持付费秒杀，请选择普通购买");
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

        // 阶段一【库存引导】：只负责确保 Redis 里有权威库存（键已存在则是纯读，无写入）。
        // 与阶段二的扣减彻底分离：库存的初始化只发生在这里，抢购脚本只做"判断 + 扣减"。
        // 抢购脚本内仍保留同规则的兜底引导分支，用于兜住"本次引导之后、扣减之前"键刚好过期的极小窗口。
        warmUpSeckillStock(voucherId);
        // 阶段一补充【已购集合预热】：一人一单第一道闸，与库存是两套独立语义
        warmUpSeckillCache(voucherId, config);

        // 先收款：钱不到位就不去占稀缺名额 —— 扣款是最易失败的跨服务动作，放到 Lua 之后要靠补偿还名额
        //    token 在收款之前生成：它既是抢购凭证，也是这一次收款的幂等键（同一 token 重复到达不会重复扣款）
        String token = IdUtil.fastSimpleUUID();
        // 四段式埋点第 1 段【入口】：用 token 作为 bizId（它同时是抢购凭证与幂等键，天然可串联全链路）
        long traceStart = BizTrace.enter(BIZ_TYPE_SECKILL, token, "voucherId=" + voucherId + " userId=" + userId);
        // 幂等键统一由 IdempotentKeys 构造：收款键与退款键必须是两个不同的值（共用会让失败补偿把他人扣款退走）
        String purchaseBizNo = IdempotentKeys.voucherSeckillPay(token);
        String refundBizNo = IdempotentKeys.seckillRefund(token);
        BizTrace.idempotent(BIZ_TYPE_SECKILL, token, purchaseBizNo, "秒杀收款键（token 维度确定）");
        boolean paid = chargeVoucherPurchase(voucher, userId, pw, purchaseBizNo);

        // 先写 PENDING 结果标记、再投递消息：顺序反了，消费者的 SUCCESS 会被随后的 PENDING 盖掉
        writeSeckillResult(SeckillResultVO.pending(token, voucherId, "抢购请求已受理，正在生成券"));

        // 阶段二【库存扣减】：Lua 原子预扣（库存判断 + 一人一单 + 扣减 + 记名一次完成），失败按返回码分流并退款
        Long code = executeSeckillScript(voucherId, config, userId);
        if (code == null) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "系统繁忙，抢购失败"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀预扣失败补偿", refundBizNo);
            BizTrace.result(BIZ_TYPE_SECKILL, token, false, "Lua 执行异常（Redis 不可用）");
            BizTrace.cost(BIZ_TYPE_SECKILL, token, traceStart);
            throw new BizIllegalException("系统繁忙，请稍后重试");
        }
        if (code == LUA_DUPLICATE) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "您已抢购过该券"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀重复抢购补偿", refundBizNo);
            BizTrace.result(BIZ_TYPE_SECKILL, token, false, "一人一单闸门命中（已抢购过）");
            BizTrace.cost(BIZ_TYPE_SECKILL, token, traceStart);
            throw new BizIllegalException("您已抢购过该券，请勿重复抢购");
        }
        if (code == LUA_SOLD_OUT) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "该券已抢完"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀已抢完补偿", refundBizNo);
            BizTrace.result(BIZ_TYPE_SECKILL, token, false, "库存不足（Redis 剩余为 0）");
            BizTrace.cost(BIZ_TYPE_SECKILL, token, traceStart);
            throw new BizIllegalException("手慢了，该券已被抢完");
        }
        if (code != LUA_SUCCESS) {
            writeSeckillResult(SeckillResultVO.failed(token, voucherId, "抢购失败"));
            refundPurchaseQuietly(paid, voucher, userId, "秒杀预扣失败补偿", refundBizNo);
            BizTrace.result(BIZ_TYPE_SECKILL, token, false, "Lua 返回未知码=" + code);
            BizTrace.cost(BIZ_TYPE_SECKILL, token, traceStart);
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
            BizTrace.result(BIZ_TYPE_SECKILL, token, false, "MQ 投递失败，已回补名额并退款");
            BizTrace.cost(BIZ_TYPE_SECKILL, token, traceStart);
            throw new BizIllegalException("系统繁忙，抢购失败，请稍后重试");
        }

        log.info("秒杀预扣成功并已投递异步落库: userId={}, voucherId={}, token={}, 售价={}分",
                userId, voucherId, token, payValueOf(voucher));
        // 埋点第 3/4 段：此处"成功"指预扣与投递成功，最终发券结果由 MQ 消费端异步决定
        BizTrace.result(BIZ_TYPE_SECKILL, token, true, "预扣成功并已投递异步落库");
        BizTrace.cost(BIZ_TYPE_SECKILL, token, traceStart);
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
        userClient.refundMoney(userId, (int) payValue, IdempotentKeys.seckillRefund(token));
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


    /** Remote validation stays outside the local voucher transaction. */
    @Override
    public boolean useMyVoucher(Long userVoucherId, Long orderId) {
        Long userId = requireLogin();
        UserVoucher voucher = ownedVoucher(userVoucherId, userId);
        if (orderId == null) throw new BadRequestException("缺少订单号");
        TradeOrderDTO order = queryOrderForVoucher(orderId, userId, voucher);
        if (!Objects.equals(order.getVoucherId(), userVoucherId)) throw new ForbiddenException("券未绑定此订单");
        if (!Integer.valueOf(1).equals(order.getStatus()) && !Integer.valueOf(2).equals(order.getStatus())) {
            throw new BizIllegalException("订单状态不允许锁券");
        }
        return voucherBindingStore.bind(userVoucherId, userId, orderId);
    }

    @Override
    public boolean refundMyVoucher(Long userVoucherId, Long orderId) {
        Long userId = requireLogin();
        UserVoucher voucher = ownedVoucher(userVoucherId, userId);
        if (orderId == null) throw new BadRequestException("缺少订单号");
        TradeOrderDTO order = queryOrderForVoucher(orderId, userId, voucher);
        if (!Objects.equals(order.getVoucherId(), userVoucherId)) throw new ForbiddenException("券未绑定此订单");
        if (!Integer.valueOf(5).equals(order.getStatus()) && !Integer.valueOf(8).equals(order.getStatus())
                && !Integer.valueOf(9).equals(order.getStatus())) {
            throw new BizIllegalException("订单尚未取消或受理退款，不能退券");
        }
        return voucherBindingStore.release(userVoucherId, userId, orderId);
    }

    private UserVoucher ownedVoucher(Long id, Long userId) {
        UserVoucher voucher = userVoucherMapper.selectById(id);
        if (voucher == null || !Objects.equals(voucher.getUserId(), userId)) throw new ForbiddenException("券不存在或不属于当前用户");
        return voucher;
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


    /** 空值兼容免费券；禁止负数免费发券或Long转int截断。 */
    private long payValueOf(Voucher voucher) {
        if (voucher == null || voucher.getPayValue() == null) {
            return 0L;
        }
        long price = voucher.getPayValue();
        if (price < 0 || price > Integer.MAX_VALUE) {
            throw new BadRequestException("券售价超出有效范围");
        }
        return price;
    }

    /** 秒杀旧协议收款；普通券使用持久化购买账本，不再调用此方法。 */
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
            metrics.compensationFailure("promotion", "refund");
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

    /**
     * 执行秒杀抢购 Lua 脚本（src/main/resources/lua/seckill.lua），把返回码原样交给调用方分流。
     *
     * 【返回】0=预扣成功（已扣库存、已记名、已发放+1）；1=库存不足；2=该用户已抢购过；
     * null=Redis 异常或脚本执行失败，调用方一律按"系统繁忙"处理（本次抢购直接失败，绝不放行未确认的预扣）。
     *
     * 【入参口径】ARGV[1]=userId，ARGV[2]=该券总名额（不是剩余量），ARGV[3]=缓存 TTL 秒数，ARGV[4]=DB 已发放行数。
     * 脚本内部用 {@code 总名额 - 已发放} 反推剩余库存（该值只在"库存键不存在"的兜底分支被读取）。
     *
     * 【误传后果】ARGV[2] 传成「剩余量」会让脚本二次扣减并把库存夹到 0：DB 里还剩的券全部不可售且永不恢复，
     * 重新预热走的是同一个 totalSeckillStock，仍然是错的 —— 不可自愈。
     *
     * 【多副本同步】脚本里的引导夹取规则与 BOOTSTRAP_SCRIPT 是同一套规则的两个副本，改一处必须同步另一处。
     */
    private Long executeSeckillScript(Long voucherId, SeckillVoucher config, Long userId) {
        try {
            long total = totalSeckillStock(voucherId, config);
            Long heldRows = userVoucherMapper.selectCount(Wrappers.<UserVoucher>lambdaQuery()
                    .eq(UserVoucher::getVoucherId, voucherId));
            long knownIssued = heldRows == null ? 0L : heldRows;
            return redisTemplate.execute(seckillScript,
                    Arrays.asList(stockKey(voucherId), orderedKey(voucherId), issuedKey(voucherId)),
                    String.valueOf(userId),
                    String.valueOf(total),
                    String.valueOf(cacheTtlSeconds(config)),
                    String.valueOf(knownIssued));
        } catch (Exception e) {
            log.error("执行秒杀 Lua 脚本失败: voucherId={}, userId={}", voucherId, userId, e);
            return null;
        }
    }

    /**
     * 预热秒杀缓存（库存/已发放 + 已购集合），各自判断、缺哪个补哪个。
     *
     * 【注意】只判断库存键是否存在会漏掉"库存键在、已购集合丢"的情况，抢过的用户会重新
     * 通过第一道闸、白跑一趟 DB；判据用 {@code COUNT}（走索引）而非每次预热拉全量
     * user_voucher 行，避免把全表级扫描放进每个秒杀请求的路径。
     *
     * 【已购集合不属于"库存引导"】：它是"一人一单"的第一道闸，与库存是两套独立语义，
     * 故本方法只负责"把缓存补齐"，不参与任何库存加减 —— 库存的初始化统一走
     * {@link #warmUpSeckillStock(Long)}。
     */
    private void warmUpSeckillCache(Long voucherId, SeckillVoucher config) {
        String orderedKey = orderedKey(voucherId);
        try {
            Long orderedSize = redisTemplate.opsForSet().size(orderedKey);
            boolean orderedExists = orderedSize != null && orderedSize > 0;
            if (orderedExists) {
                return;
            }
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
        } catch (Exception e) {
            // 预热失败不阻断主流程：已购集合最终由 uk_user_voucher 唯一键兜住
            log.warn("秒杀已购集合预热失败，将依赖 DB 唯一键兜底: voucherId={}", voucherId, e);
        }
    }

    /**
     * 秒杀库存引导（对外入口）：按 DB 权威数据重建 Redis 的「库存 + 已发放」两键。
     *
     * 【设计意图】把"库存初始化"与"库存扣减"彻底分开：
     * 初始化只在本方法里发生（写库存键，总名额与已发放都来自 DB）；抢购链路只做"判断 + 扣减"，不承担任何初始化职责。
     * 拆成两条路径的收益：读代码一眼能看出"库存是谁写的"，改夹取规则时只需看一处；
     * 而且引导可被独立复用（定时预热、运维补数据），不必借用抢购入口。
     *
     * 【兜底】抢购脚本内部仍保留"库存键缺失就按同一规则引导"的分支，
     * 用于兜住"本次引导之后、扣减之前"键刚好过期的极小窗口（见 BOOTSTRAP_SCRIPT 的多副本同步约束）。
     *
     * 【幂等】库存键存在时是纯读（不写任何 key），且库存键与已发放键同生共死，可在每次抢购入口安全调用。
     *
     * @return true 表示本次确实做了引导（可用于告警"Redis 曾丢数据"）
     */
    @Override
    public boolean warmUpSeckillStock(Long voucherId) {
        if (voucherId == null) {
            return false;
        }
        SeckillVoucher config = seckillVoucherMapper.selectById(voucherId);
        if (config == null) {
            return false;
        }
        String stockKey = stockKey(voucherId);
        String issuedKey = issuedKey(voucherId);
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(stockKey))) {
                // 库存键在 → 已发放键必然也在（两者永远同生共死），无需任何写入
                return false;
            }
            // 库存键缺失只可能是"Redis 整体丢数据"：此时 Redis 已发放必然为 0，
            // 必须把 DB 已知的已发放量作为保守下限，否则会把已卖出的名额重新放出来 → 超卖
            long total = totalSeckillStock(voucherId, config);
            Long heldRows = userVoucherMapper.selectCount(Wrappers.<UserVoucher>lambdaQuery()
                    .eq(UserVoucher::getVoucherId, voucherId));
            long knownIssued = heldRows == null ? 0L : heldRows;
            Long bootstrapped = redisTemplate.execute(BOOTSTRAP_SCRIPT,
                    Arrays.asList(stockKey, issuedKey),
                    String.valueOf(total),
                    String.valueOf(cacheTtlSeconds(config)),
                    String.valueOf(knownIssued));
            log.warn("【秒杀缓存重建】库存键缺失（Redis 丢数据），已按 DB 权威数据引导: "
                            + "voucherId={}, 总名额={}, DB已发放={}, 是否引导={}",
                    voucherId, total, knownIssued, bootstrapped);
            return bootstrapped != null && bootstrapped > 0;
        } catch (Exception e) {
            // 引导失败不抛：抢购脚本内仍有同规则兜底分支；但我们不静默 —— 打 ERROR 便于告警
            log.error("秒杀库存引导失败（抢购脚本内将按同一规则兜底）: voucherId={}", voucherId, e);
            return false;
        }
    }

    /**
     * 计算该券的总名额 T（不是剩余量），供引导脚本与抢购脚本使用。
     *
     * 【口径】T = DB 剩余量（config.stock）+ DB 已发放行数（user_voucher 行数）。
     * DB 剩余量是原子的权威扣减结果（{@code deductStock} 带 {@code stock > 0} 守卫），
     * 已发放行数是唯一可信的"已放出多少"，两者相加即总名额。
     *
     * 【为什么必须传总名额】Redis 侧的恒等式是 {@code 库存 + 已发放 = 总名额}，
     * 两个脚本都用「总名额 - Redis 已发放」反推库存，所以 Java 必须传总名额。
     *
     * 【误传后果】传成「剩余量」等于把已扣减过的 config.stock 再减一次已发放行数，
     * 该值又被脚本当成总名额再减一次 Redis 已发放：库存被二次扣减后夹到 0，
     * DB 里还剩的券全部不可售且永不恢复 —— 重新预热走的是同一个函数，仍然是错的，无法自愈。
     *
     * @return 总名额（≥ 0）；统计失败时返回 0（fail-closed：没有依据时宁可少卖，绝不凭猜测放行）
     */
    private long totalSeckillStock(Long voucherId, SeckillVoucher config) {
        long stock = config.getStock() == null ? 0L : config.getStock();
        try {
            Long issued = userVoucherMapper.selectCount(Wrappers.<UserVoucher>lambdaQuery()
                    .eq(UserVoucher::getVoucherId, voucherId));
            long issuedCount = issued == null ? 0L : issued;
            return Math.max(stock, 0L) + Math.max(issuedCount, 0L);
        } catch (Exception e) {
            // 查不出来就按 0 处理（fail-closed）：没有依据时宁可少卖，绝不凭 DB 剩余量放行
            log.error("统计券已发放数量失败，总名额按 0 处理（宁可少卖不超卖）: voucherId={}", voucherId, e);
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
