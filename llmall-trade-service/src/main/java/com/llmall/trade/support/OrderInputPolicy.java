package com.llmall.trade.support;

import cn.hutool.core.util.StrUtil;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.common.exception.BadRequestException;
import com.llmall.trade.domain.dto.OrderFormDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 下单输入和分金额边界；在任何持久化、扣库存之前执行。 */
public final class OrderInputPolicy {
    private static final int MAX_LINES = 50;
    private static final int MAX_QUANTITY = 99;

    private OrderInputPolicy() { }

    public static OrderFormDTO copyAndValidate(OrderFormDTO form) {
        if (form == null || form.getDetails() == null || form.getDetails().isEmpty()
                || form.getDetails().size() > MAX_LINES) {
            throw new BadRequestException("下单商品数量必须在 1~50 行之间");
        }
        Integer payment = form.getPaymentType();
        if (payment == null || payment < 1 || payment > 3) {
            throw new BadRequestException("不支持的支付方式");
        }
        String token = StrUtil.trimToNull(form.getClientToken());
        if (token != null && token.length() > 64) {
            throw new BadRequestException("下单令牌不能超过 64 个字符");
        }
        if (invalidId(form.getAddressId()) || invalidId(form.getUserVoucherId())) {
            throw new BadRequestException("地址或优惠券编号无效");
        }
        Set<Long> ids = new HashSet<>();
        List<OrderDetailDTO> details = new ArrayList<>(form.getDetails().size());
        for (OrderDetailDTO detail : form.getDetails()) {
            if (detail == null || detail.getItemId() == null || detail.getItemId() <= 0
                    || detail.getNum() == null || detail.getNum() < 1 || detail.getNum() > MAX_QUANTITY) {
                throw new BadRequestException("商品编号必须为正数，购买数量必须在 1~99 之间");
            }
            if (!ids.add(detail.getItemId())) {
                throw new BadRequestException("同一商品不能重复提交");
            }
            // 库存业务号和门店由服务端生成，不接受客户端提供的这些字段。
            details.add(new OrderDetailDTO().setItemId(detail.getItemId()).setNum(detail.getNum()));
        }
        OrderFormDTO copy = new OrderFormDTO();
        copy.setAddressId(form.getAddressId());
        copy.setUserVoucherId(form.getUserVoucherId());
        copy.setPaymentType(payment);
        copy.setClientToken(token);
        copy.setDetails(details);
        return copy;
    }

    public static int calculateTotal(List<ItemDTO> items, Map<Long, Integer> quantities) {
        if (items == null || items.size() != quantities.size() || items.isEmpty()) {
            throw new BadRequestException("商品信息不完整，请刷新后重试");
        }
        Set<Long> seen = new HashSet<>();
        Long shopId = null;
        Integer orderType = null;
        long total = 0;
        for (ItemDTO item : items) {
            if (item == null || !quantities.containsKey(item.getId()) || !seen.add(item.getId())
                    || item.getPrice() == null || item.getPrice() < 0 || !Integer.valueOf(1).equals(item.getStatus())) {
                throw new BadRequestException("商品信息无效或已下架，请刷新后重试");
            }
            int type = item.getType() == null ? 1 : item.getType();
            if ((type != 1 && type != 2) || invalidId(item.getShopId())
                    || (type == 2 && item.getShopId() == null)) {
                throw new BadRequestException("商品类型或门店信息无效");
            }
            if (orderType == null) {
                orderType = type;
                shopId = item.getShopId();
            } else if (orderType != type || !Objects.equals(shopId, item.getShopId())) {
                throw new BadRequestException("一笔订单只支持同一门店、同一履约类型的商品");
            }
            total += (long) item.getPrice() * quantities.get(item.getId());
            if (total > Integer.MAX_VALUE) {
                throw new BadRequestException("订单金额超过支持上限");
            }
        }
        return payable((int) total, null);
    }

    public static int payable(int total, Long discount) {
        if (discount != null && (discount <= 0 || discount > Integer.MAX_VALUE)) {
            throw new BadRequestException("优惠券面额无效或超过支持上限");
        }
        long payable = (long) total - (discount == null ? 0 : discount);
        if (payable <= 0) {
            throw new BadRequestException("暂不支持零元实付订单，请调整商品或优惠券");
        }
        return (int) payable;
    }

    private static boolean invalidId(Long id) {
        return id != null && id <= 0;
    }
}
