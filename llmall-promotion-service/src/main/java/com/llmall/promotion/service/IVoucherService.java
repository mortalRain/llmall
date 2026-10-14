package com.llmall.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.promotion.domain.po.Voucher;
import com.llmall.promotion.domain.vo.SeckillResultVO;
import com.llmall.promotion.domain.vo.UserVoucherVO;
import com.llmall.promotion.domain.vo.VoucherVO;
import com.llmall.promotion.mq.SeckillMessage;

import java.util.List;

/**
 * 营销域券服务：券查询、领取（含付费券收款）、秒杀、我的券与核销退款。
 */
public interface IVoucherService extends IService<Voucher> {

    /** 可领取的券列表（status=1，可按门店/类型过滤） */
    List<VoucherVO> listAvailable(Long shopId, Integer type);

    /** 券详情 */
    VoucherVO getVoucherDetail(Long voucherId);

    /** 领取普通券，返回 user_voucher.id */
    Long receive(Long voucherId);

    /** 秒杀抢券：返回秒杀凭证 token（落库异步完成） */
    String seckill(Long voucherId);

    /**
     * 领取普通券（付费券需校验支付密码），返回 user_voucher.id。
     *
     * 付费券走"先收款、再发券"：收款失败不发券，收款成功但发券失败则显式退款。
     *
     * @param pw 支付密码：仅当券的 payValue &gt; 0 时必填，免费券传 null 即可
     */
    Long receive(Long voucherId, String pw);

    /**
     * 秒杀抢券（付费券需校验支付密码）：返回秒杀凭证 token。
     *
     * 顺序固定为"先收款 → 再抢名额"；抢名额失败（已抢完/已持有/系统繁忙）立即退款并回补名额。
     *
     * @param pw 支付密码：仅当券的 payValue &gt; 0 时必填，免费券传 null 即可
     */
    String seckill(Long voucherId, String pw);

    /**
     * 付费券的购买退款（MQ 落库失败时的补偿，幂等）。
     *
     * @param token 秒杀凭证（既当幂等键，也当对账线索）
     * @return true 表示本次真的发起了退款
     */
    boolean refundSeckillPurchase(String token, Long voucherId, Long userId, String scene);

    /** 查询秒杀异步处理结果 */
    SeckillResultVO querySeckillResult(Long voucherId, String token);

    /** 我的券列表（status 为 null 返回全部；过期券按 expire_time 判定为 3） */
    List<UserVoucherVO> listMyVouchers(Integer status);

    /**
     * 我的券详情（券实例 id），供交易域下单前回查券的真实状态与面额。
     *
     * @throws com.llmall.common.exception.ForbiddenException 券不存在或不属于当前登录用户
     */
    UserVoucherVO getMyVoucher(Long userVoucherId);

    /**
     * 核销我的券，返回是否核销成功（重复提交同一订单视为成功，幂等）。
     *
     * 【注意】只允许订单链路调用（HTTP 入口在 /internal/vouchers 下，校验内部凭证），
     * orderId 必传：本方法会回查交易订单的存在、归属、门店、状态与 voucher_id 一致性。
     */
    boolean useMyVoucher(Long userVoucherId, Long orderId);

    /** 订单取消/退款时退回券（状态 2 → 1，幂等）；同样只允许订单链路调用且 orderId 必传 */
    boolean refundMyVoucher(Long userVoucherId, Long orderId);

    /**
     * 处理秒杀落库消息（MQ 消费端唯一入口）。
     *
     * @return 新落库的 user_voucher.id；若该 token 已被处理过（重复投递）返回 null
     * @throws com.llmall.common.exception.CommonException 业务性失败（库存不足/已持有）——重试无意义，调用方应直接 ack
     * @throws RuntimeException                          系统性失败（DB 不可用等）——调用方应重投重试
     */
    Long handleSeckillOrder(SeckillMessage message);

    /** 标记秒杀成功（写 Redis 结果标记，供 /vouchers/seckill/{id}/result 轮询） */
    void markSeckillSuccess(String token, Long voucherId, Long userVoucherId);

    /** 标记秒杀失败（写 Redis 结果标记 + 失败原因） */
    void markSeckillFailed(String token, Long voucherId, String message);

    /** 回补 Redis 预扣（库存 +1、已购集合移除该用户），用于落库彻底失败时保持 Redis 与 DB 一致 */
    void compensateSeckillDeduction(Long voucherId, Long userId);
}
