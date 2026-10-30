package com.llmall.common.constant;

/**
 * 跨服务业务键的统一构造入口，数据库流水或消费记录负责最终去重。
 * 同一尝试的重试必须复用原键；退款后的新购买必须使用新尝试。
 * 业务唯一性与资金尝试唯一性不同：一人一券由券表约束，不能据此复用已退款扣款。
 * 需要跨进程恢复的尝试ID还必须持久化，仅生成业务键不能保证恢复。
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

    /** 普通券收款键前缀；新请求还需包含购买尝试ID。 */
    public static final String VOUCHER_BUY_PREFIX = "VOUCHER_BUY_";

    /** 付费券退款键前缀，完整格式：{@code VOUCHER_REFUND_<券id>_<用户id>_<尝试uuid>} */
    public static final String VOUCHER_REFUND_PREFIX = "VOUCHER_REFUND_";

    /** 秒杀券收款键前缀，完整格式：{@code VOUCHER-SECKILL-<token>} */
    public static final String VOUCHER_SECKILL_PREFIX = "VOUCHER-SECKILL-";

    /** 秒杀券退款键前缀，完整格式：{@code SECKILL_REFUND:<token>} */
    public static final String SECKILL_REFUND_PREFIX = "SECKILL_REFUND:";

    /** 历史收款键；不能用于退款后可重试的新购买流程。 */
    public static String voucherBuy(Long voucherId, Long userId) {
        return VOUCHER_BUY_PREFIX + voucherId + "_" + userId;
    }

    /** 同一购买尝试始终使用同一扣款键；退款后重新购买必须使用新尝试。 */
    public static String voucherBuy(Long voucherId, Long userId, String attemptId) {
        return voucherBuy(voucherId, userId) + "_" + attemptId;
    }

    /** 退款与扣款使用同一个attemptId；退款重试不得重新生成它。 */
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
