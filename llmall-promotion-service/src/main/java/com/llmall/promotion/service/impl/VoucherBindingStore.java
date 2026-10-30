package com.llmall.promotion.service.impl;

import com.llmall.promotion.mapper.UserVoucherMapper;
import com.llmall.promotion.domain.po.UserVoucher;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

/** Remote order validation precedes this bean. All local bindings serialize on the voucher row. */
@Service @RequiredArgsConstructor
public class VoucherBindingStore {
    private final UserVoucherMapper vouchers;

    @Transactional(rollbackFor = Exception.class)
    public boolean bind(Long id, Long userId, Long orderId) {
        UserVoucher voucher=lock(id,userId,orderId);
        if(vouchers.canceled(id,orderId)>0) throw new BizIllegalException("该订单已退券，不能重新锁券");
        if(Integer.valueOf(UserVoucher.STATUS_USED).equals(voucher.getStatus()) && Objects.equals(orderId,voucher.getOrderId())) return true;
        if(!Integer.valueOf(UserVoucher.STATUS_UNUSED).equals(voucher.getStatus())
                || (voucher.getExpireTime()!=null && voucher.getExpireTime().isBefore(LocalDateTime.now()))) {
            throw new BizIllegalException("券已使用或已过期，无法锁定");
        }
        int rows=vouchers.update(null,Wrappers.<UserVoucher>lambdaUpdate().eq(UserVoucher::getId,id)
                .eq(UserVoucher::getStatus,UserVoucher.STATUS_UNUSED).set(UserVoucher::getStatus,UserVoucher.STATUS_USED)
                .set(UserVoucher::getOrderId,orderId).set(UserVoucher::getUseTime,LocalDateTime.now()));
        if(rows!=1) throw new BizIllegalException("券状态已变化");
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean release(Long id, Long userId, Long orderId) {
        UserVoucher voucher=lock(id,userId,orderId);
        // Commit the cancellation even if binding has not arrived, or the voucher was reused elsewhere.
        vouchers.cancelBinding(id,orderId);
        if(Integer.valueOf(UserVoucher.STATUS_USED).equals(voucher.getStatus()) && Objects.equals(orderId,voucher.getOrderId())) {
            int rows=vouchers.update(null,Wrappers.<UserVoucher>lambdaUpdate().eq(UserVoucher::getId,id)
                    .eq(UserVoucher::getStatus,UserVoucher.STATUS_USED).eq(UserVoucher::getOrderId,orderId)
                    .set(UserVoucher::getStatus,UserVoucher.STATUS_UNUSED));
            if(rows!=1) throw new BizIllegalException("退券未完成");
        }
        return true;
    }

    private UserVoucher lock(Long id,Long userId,Long orderId) {
        if(orderId==null) throw new BizIllegalException("订单号不能为空");
        UserVoucher voucher=vouchers.lockById(id);
        if(voucher==null || userId==null || !Objects.equals(voucher.getUserId(),userId)) throw new ForbiddenException("券不存在或不属于当前用户");
        return voucher;
    }
}
