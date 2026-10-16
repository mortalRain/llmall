package com.llmall.common.constant;

/**
 * 全局幂等键统一收口：跨服务的幂等键前缀与完整格式只在本类定义，业务代码一律调用本类方法取键。
 *
 * 【键的构成】三段拼接，缺一不可：
 * 1. 业务前缀：标明该动作属于哪个业务域，是对账时按业务归类查询的依据；
 * 2. 业务主键：支付单 id、券 id + 用户 id、订单号等，由服务端产生；
 * 3. 口径区分：同一业务的不同口径必须拼成不同字符串（扣款与退款、收款与退款、关单回滚与退款回滚）。
 *
 * 【为什么必须集中定义而不能各处手拼】键一旦允许在各服务手拼，会出现三类失效：
 * 1. 扣款键与退款键共用一个格式，并发下第二个请求的退款会把第一个请求已成功的扣款退走，直接资损；
 * 2. 键里混入客户端可控的 traceId 或按次随机值，重放与重试会落到不同的键上，重复扣款；
 * 3. 改一处漏一处，前缀在 A 服务改了、B 服务的对账查询仍用旧前缀，对账静默查不到、也无法按前缀归类。
 * 集中到本类后键的形态只有一个定义点，评审时能一眼看全。
 *
 * 【两条硬性设计约束】
 * 1. 扣款键必须确定（同一逻辑动作恒定），退款键必须与扣款键不同值。
 *    扣款键确定才能保证"重复点击 / 网络重发"不重复扣款；
 *    退款键不同值才能保证"某次尝试失败要退款"时，不会把另一次尝试的扣款退掉。
 * 2. 禁止把客户端可控的值（traceId / 请求头 / 前端传参）放进键，也禁止用按次随机值当扣款键。
 *    幂等键一旦可被调用方选择，就等于把"是否重复扣款"的决定权交给了调用方；
 *    只有业务口径本身就按次区分时才允许随机成分，见本类 voucherRefund。
 *
 * 【生命周期】这些键最终都落到下游用户域 {@code balance_flow.biz_no} 或库存域 {@code stock_flow}
 * 的唯一索引上：它们不是 Redis 键，而是数据库唯一键 ——
 * 生命周期由数据保留策略决定，不依赖 Redis TTL，不会因缓存丢失而失效
 * （这正是把幂等锚定在数据库而非 Redis 的原因：缓存可以丢，唯一键不会）。
 * 落库位置、唯一索引与保留期：
 * 1. 支付扣款 / 退款：balance_flow，uk_biz(biz_type, biz_no)，随流水永久保留（对账依据）；
 * 2. 领券收款 / 退款：balance_flow，uk_biz(biz_type, biz_no)，同上；
 * 3. 库存扣减 / 回补：stock_flow，uk_biz(biz_type, biz_id, item_id)，同上；
 * 4. MQ 消费幂等：consume_record，uk_consume(biz_type, biz_id, consumer)，同上。
 * 【注意】需要 TTL 的是与键配套的 Redis 锁与结果标记，不是键本身，它们登记在本类第五分区。
 *
 * @author 同城生活
 * @since 2026-10
 */
public final class IdempotentKeys {

    private IdempotentKeys() {
    }

    // 一、支付域（llmall-pay-service）：扣款键必须确定，退款键必须与扣款键不同值

    /** 余额支付扣款键前缀，完整格式：{@code PAY:<支付单id>:<金额>} */
    public static final String PAY_DEDUCT_PREFIX = "PAY:";

    /** 支付失败补偿退款键前缀，完整格式：{@code PAYFAIL:<扣款键>} */
    public static final String PAY_FAIL_REFUND_PREFIX = "PAYFAIL:";

    /**
     * 余额支付扣款幂等键。
     *
     * 【为什么必须订单维度确定】用 traceId 或随机数时，"扣款已成功但响应丢失"会被判定为未扣款，
     * 用户重试即以新键再扣一次。用确定键后重试落到同一键，下游唯一索引跳过，不会二次扣款。
     *
     * 【为什么金额也进键】同一支付单金额不会变，但它能在金额被篡改时避免意外命中另一笔
     * 金额不同的历史流水，是一层廉价的一致性保护。
     *
     * @param payOrderId 支付单 id，服务端生成，严禁用前端传参代替
     * @param amount     扣款金额，单位分
     */
    public static String payDeduct(Long payOrderId, Integer amount) {
        return PAY_DEDUCT_PREFIX + payOrderId + ":" + amount;
    }

    /**
     * 支付失败补偿退款幂等键：与扣款键一一对应（同一支付单的补偿重复执行只退一次）。
     *
     * 【注意】它与 {@link #payDeduct} 必须是不同字符串：若共用同一个键，
     * 下游会把"退款"当成"已扣过款"而拒绝入账，用户的钱就退不回来。
     */
    public static String payFailRefund(String deductBizNo) {
        return PAY_FAIL_REFUND_PREFIX + deductBizNo;
    }

    // 二、营销域（llmall-promotion-service）：收款键确定，退款键按次唯一

    /** 付费券收款键前缀，完整格式：{@code VOUCHER_BUY_<券id>_<用户id>} */
    public static final String VOUCHER_BUY_PREFIX = "VOUCHER_BUY_";

    /** 付费券退款键前缀，完整格式：{@code VOUCHER_REFUND_<券id>_<用户id>_<尝试uuid>} */
    public static final String VOUCHER_REFUND_PREFIX = "VOUCHER_REFUND_";

    /** 秒杀券收款键前缀，完整格式：{@code VOUCHER-SECKILL-<token>} */
    public static final String VOUCHER_SECKILL_PREFIX = "VOUCHER-SECKILL-";

    /** 秒杀券退款键前缀，完整格式：{@code SECKILL_REFUND:<token>} */
    public static final String SECKILL_REFUND_PREFIX = "SECKILL_REFUND:";

    /**
     * 付费券收款幂等键（确定性）：同一用户对同一券恒定，保证重复点击不重复扣款。
     *
     * 【为什么按"一人一张券"取】{@code uk_user_voucher(user_id, voucher_id)} 已保证
     * 一个用户对一张券只能买一次，因此用（券 id, 用户 id）作键与业务唯一性完全对齐。
     */
    public static String voucherBuy(Long voucherId, Long userId) {
        return VOUCHER_BUY_PREFIX + voucherId + "_" + userId;
    }

    /**
     * 付费券退款幂等键（按次唯一）。
     *
     * 【为什么不能沿用收款键】并发的第二个请求会因为"扣款被下游幂等跳过，但仍返回成功"
     * 而误判为自己付过钱；它失败后若用同一个键退款，就会把第一个请求已经成功的扣款退回去，
     * 等于用户不付钱就拿到付费券。故退款键必须按"这一次尝试"唯一。
     *
     * @param attemptId 本次尝试的唯一标识（调用方生成，如 {@code IdUtil.fastSimpleUUID()}）
     */
    public static String voucherRefund(Long voucherId, Long userId, String attemptId) {
        return VOUCHER_REFUND_PREFIX + voucherId + "_" + userId + "_" + attemptId;
    }

    /** 秒杀券收款幂等键：token 在收款前生成，既是抢购凭证也是一次收款的幂等键 */
    public static String voucherSeckillPay(String token) {
        return VOUCHER_SECKILL_PREFIX + token;
    }

    /**
     * 秒杀券退款幂等键。
     *
     * 【为什么与收款键不同值】同一 token 的收款与退款都发生，共用键会被下游当成重复而拒绝退款。
     */
    public static String seckillRefund(String token) {
        return SECKILL_REFUND_PREFIX + token;
    }

    // 三、交易域（llmall-trade-service）：退款入账按订单维度确定，重复退款只入账一次

    /** 订单退款入账键前缀，完整格式：{@code ORDER_REFUND:<订单id>} */
    public static final String ORDER_REFUND_PREFIX = "ORDER_REFUND:";

    /** 订单退款入账幂等键：按订单维度确定，重复退款只入账一次 */
    public static String orderRefund(Long orderId) {
        return ORDER_REFUND_PREFIX + orderId;
    }

    // 四、库存域（llmall-item-service）：以 biz_type 区分扣减 / 关单回滚 / 退款回滚三个口径

    /** 库存扣减业务类型（写入 stock_flow.biz_type，与订单号组成幂等键） */
    public static final String STOCK_BIZ_ORDER_CREATED = MqConstants.BIZ_ORDER_CREATED;

    /** 取消关单回滚库存的业务类型 */
    public static final String STOCK_BIZ_ORDER_CANCELED = MqConstants.BIZ_ORDER_CANCELED;

    /**
     * 退款回滚库存的业务类型。
     *
     * 【为什么不复用"取消关单"的类型】库存流水的幂等键是 (biz_type, biz_id, item_id)，
     * 分开业务号才能区分"关单回滚"与"退款回滚"，也保证"取消后又退款"不会互相顶掉幂等记录。
     */
    public static final String STOCK_BIZ_ORDER_REFUNDED = MqConstants.BIZ_ORDER_REFUNDED;

    // 五、Redis 键：只有锁与结果标记需要 TTL，键本身是数据库唯一键、不依赖 Redis

    /**
     * 支付锁：粒度是"支付单"，不是用户也不是全局。
     *
     * 【取值依据】必须远大于一次余额支付的正常耗时 —— 一单支付含两次跨服务调用，
     * 若 TTL 小于调用耗时，锁会在业务还没结束时先过期，第二个请求就能同时进来。
     * 【调大后果】持锁进程崩溃后，同一支付单在 TTL 内无法再被处理，锁的恢复更慢。
     * 【调小后果】下游一次抖动就可能提前放锁，锁的保护作用失效。
     * 【注意】锁只做"同一支付单串行化"的优化，重复扣款的最终裁决点是下游流水表的唯一索引；
     * 锁失效不会造成资损，但会放大下游的并发冲突。
     * 【多副本】本字符串在 llmall-pay-service 另有一份同值常量，改一处必须同步另一处。
     */
    public static final String REDIS_PAY_LOCK_PREFIX = "pay:lock:";
    public static final long REDIS_PAY_LOCK_TTL_SECONDS = 30L;

    /**
     * 购物车缓存键：Redis Hash，field = itemId。
     *
     * 【取值依据】TTL 口径与 {@code CartRedisConstants.CART_CACHE_TTL} 一致（滑动续期）：
     * 现役 Redis 是 noeviction，没有 TTL 的 key 会永久驻留，写满之后所有写入直接失败。
     * 【多副本】本字符串与 llmall-cart-service 的 CART_CACHE_KEY 同值，改一处必须同步另一处，
     * 否则缓存的写入与读取会落在两个不同的 key 上。
     */
    public static final String REDIS_CART_CACHE_PREFIX = "cache:cart:";

    /**
     * 全量会话失效水位：key = 前缀 + userId，value = 失效时间戳（毫秒）。
     *
     * 【用途】令牌签发时间早于水位即视为失效，使一次改密能让该用户全部已签发的 access 立即失效。
     * 【取值依据】只需覆盖 access 令牌的最大寿命（{@code llmall.jwt.tokenTTL}），
     * 本值在其之上留一倍余量，用于吸收令牌侧与水位侧的时钟偏差。
     * 【调大后果】水位 key 驻留更久，无功能影响。
     * 【调小后果】出现"水位已过期、旧 access 尚未过期"的窗口，该窗口内改密前签发的令牌仍可通过。
     * 【关联配置】{@code llmall.jwt.tokenTTL} 调大时本值必须同步调大。
     * 【多副本】真正读写水位的是 AuthConstants.REDIS_GLOBAL_LOGOUT_AT，本常量只是登记；
     * 两者取值必须保持一致，改一处必须同步另一处。
     */
    public static final String REDIS_GLOBAL_LOGOUT_PREFIX = "auth:logoutat:";
    public static final long REDIS_GLOBAL_LOGOUT_TTL_HOURS = 1L;
}
