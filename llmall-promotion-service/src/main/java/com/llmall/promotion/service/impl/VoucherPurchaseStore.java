package com.llmall.promotion.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ConflictException;
import com.llmall.promotion.domain.po.*;
import com.llmall.promotion.mapper.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Local state transitions only. Funds calls belong to the coordinator, outside these transactions. */
@Service
@RequiredArgsConstructor
public class VoucherPurchaseStore {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private final VoucherPurchaseMapper purchases;
    private final VoucherMapper vouchers;
    private final SeckillVoucherMapper seckill;
    private final UserVoucherMapper issued;
    @Value("${llmall.promotion.default-valid-days:30}")
    private int defaultValidDays = 30;

    @Getter @RequiredArgsConstructor
    public static class Claim {
        private final VoucherPurchase purchase;
        private final boolean created;
    }

    @Transactional(rollbackFor = Exception.class)
    public Claim claim(Long userId, Long voucherId, String requestId) {
        if (userId == null || userId <= 0 || voucherId == null || voucherId <= 0) throw new BadRequestException("用户或券ID非法");
        if (requestId != null) validateRequestId(requestId);
        // This must be the first DB read: an earlier RR snapshot could miss a preceding claim after the lock wait.
        Voucher voucher = vouchers.lockById(voucherId);
        if (requestId != null) {
            VoucherPurchase existing = purchases.find(userId, requestId);
            if (existing != null) {
                assertSameVoucher(existing, voucherId);
                return new Claim(existing, false);
            }
        }
        if (voucher == null || !Integer.valueOf(Voucher.STATUS_ON).equals(voucher.getStatus())) {
            throw new BizIllegalException("优惠券不存在或已下架");
        }
        if (seckill.selectById(voucherId) != null) throw new BizIllegalException("秒杀券请通过秒杀入口领取");
        long price = voucher.getPayValue() == null ? 0 : voucher.getPayValue();
        if (price < 0 || price > Integer.MAX_VALUE) throw new BadRequestException("券售价超出有效范围");
        if (requestId == null && price > 0) throw new BadRequestException("付费领券必须提供requestId");
        if (issued.selectCount(Wrappers.<UserVoucher>lambdaQuery().eq(UserVoucher::getUserId, userId)
                .eq(UserVoucher::getVoucherId, voucherId)) > 0) throw new ConflictException("您已领取过该券");
        if (purchases.findActive(userId, voucherId) != null) throw new ConflictException("已有购买请求处理中，请查询原请求");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        VoucherPurchase p = new VoucherPurchase().setId(IdWorker.getId()).setUserId(userId).setVoucherId(voucherId)
                .setRequestId(requestId == null ? UUID.randomUUID().toString() : requestId).setAmount((int) price)
                .setShopId(voucher.getShopId()).setExpireTime(expireTime(voucher, now)).setState(VoucherPurchase.ACTIVE)
                .setCreateTime(now).setNextCheckTime(now.plusMinutes(2));
        if (purchases.insert(p) != 1) throw new BizIllegalException("无法登记购买请求");
        return new Claim(p, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long issue(Long id) {
        VoucherPurchase p = requireLocked(id);
        if (VoucherPurchase.ISSUED.equals(p.getState())) return requireCoupon(p);
        if (!VoucherPurchase.ACTIVE.equals(p.getState())) throw new ConflictException("购买请求正在撤销或已取消");
        LocalDateTime now = LocalDateTime.now();
        if (!p.getExpireTime().isAfter(now)) throw new BizIllegalException("优惠券有效期已过，购买将撤销");
        UserVoucher coupon = new UserVoucher().setUserId(p.getUserId()).setVoucherId(p.getVoucherId())
                .setShopId(p.getShopId()).setStatus(UserVoucher.STATUS_UNUSED).setReceiveTime(now).setExpireTime(p.getExpireTime());
        if (issued.insert(coupon) != 1 || coupon.getId() == null) throw new BizIllegalException("发券未确认");
        update(new VoucherPurchase().setId(id).setState(VoucherPurchase.ISSUED).setUserVoucherId(coupon.getId()));
        return coupon.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public VoucherPurchase beginCancel(Long id) {
        VoucherPurchase p = requireLocked(id);
        if (VoucherPurchase.ISSUED.equals(p.getState()) || VoucherPurchase.CANCELED.equals(p.getState())) return null;
        if (!VoucherPurchase.ACTIVE.equals(p.getState()) && !VoucherPurchase.CANCELING.equals(p.getState())) {
            throw new BizIllegalException("购买状态非法");
        }
        p.setState(VoucherPurchase.CANCELING).setNextCheckTime(LocalDateTime.now().plusSeconds(30));
        update(new VoucherPurchase().setId(id).setState(p.getState()).setNextCheckTime(p.getNextCheckTime()));
        return p;
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishCancel(Long id) {
        VoucherPurchase p = requireLocked(id);
        if (VoucherPurchase.CANCELED.equals(p.getState())) return;
        if (!VoucherPurchase.CANCELING.equals(p.getState())) throw new BizIllegalException("购买撤销状态非法");
        update(new VoucherPurchase().setId(id).setState(VoucherPurchase.CANCELED));
    }

    public VoucherPurchase find(Long userId, String requestId) {
        validateRequestId(requestId);
        return purchases.find(userId, requestId);
    }
    public VoucherPurchase current(Long id) { return purchases.selectById(id); }
    public static void assertSameVoucher(VoucherPurchase p, Long voucherId) {
        if (!Objects.equals(p.getVoucherId(), voucherId)) throw new BadRequestException("同一requestId不能用于不同优惠券");
    }
    public static Long requireCoupon(VoucherPurchase p) {
        if (p.getUserVoucherId() == null) throw new BizIllegalException("成功购买缺少券实例，需核对");
        return p.getUserVoucherId();
    }
    private VoucherPurchase requireLocked(Long id) {
        VoucherPurchase p = purchases.lock(id);
        if (p == null) throw new BizIllegalException("购买请求不存在");
        return p;
    }
    private void update(VoucherPurchase value) {
        if (purchases.updateById(value) != 1) throw new BizIllegalException("购买状态更新未确认");
    }
    private static void validateRequestId(String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) throw new BadRequestException("requestId须为16至64位字母、数字、下划线或横线");
    }
    private LocalDateTime expireTime(Voucher voucher, LocalDateTime now) {
        int days = defaultValidDays;
        if (voucher.getRules() != null && !voucher.getRules().trim().isEmpty()) {
            try {
                var rule = JSONUtil.parseObj(voucher.getRules());
                Integer configured = rule.getInt("有效天数", rule.getInt("validDays", null));
                if (configured != null && configured > 0) days = configured;
            } catch (RuntimeException ignored) { /* Malformed legacy rules use the configured default. */ }
        }
        LocalDateTime expiry = now.plusDays(days);
        if (days <= 0 || expiry.getYear() > 9999) throw new BadRequestException("优惠券有效期超出支持范围");
        return expiry;
    }
}
