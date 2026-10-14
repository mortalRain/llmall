package com.llmall.api.fallback;

import com.llmall.api.client.ShopClient;
import com.llmall.api.dto.ShopDTO;
import com.llmall.api.dto.ShopPageDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 门店服务降级。
 * 门店信息属于"展示增强"：查不到门店应降级为空/空集合，不能因为门店服务抖动就让整个页面 500。
 */
@Slf4j
@Component
public class ShopClientFallback implements FallbackFactory<ShopClient> {

    @Override
    public ShopClient create(Throwable cause) {
        return new ShopClient() {
            @Override
            public ShopDTO queryShopById(Long id) {
                log.warn("远程调用 ShopClient#queryShopById 失败, id={}", id, cause);
                return null;
            }

            @Override
            public List<ShopDTO> queryShopByIds(Collection<Long> ids) {
                log.warn("远程调用 ShopClient#queryShopByIds 失败, ids={}", ids, cause);
                return CollUtils.emptyList();
            }

            @Override
            public boolean isOrderConsumed(Long orderId) {
                log.warn("远程调用 ShopClient#isOrderConsumed 失败, orderId={}", orderId, cause);
                // 对账类查询禁止返回猜测的答案：调用方拿到 true 会认为门店已处理，
                // 降级期间的一致性缺口将被伪装成正常。抛异常让调用方区分已消费/未消费/未知三态，
                // 未知不计入"均已处理"，保留待下轮重试。
                throw new BizIllegalException("门店服务不可用，无法确认订单是否已被消费，orderId=" + orderId);
            }

            @Override
            public ShopPageDTO queryShopPage(Integer page, Integer size) {
                log.warn("远程调用 ShopClient#queryShopPage 失败, page={}, size={}", page, size, cause);
                // 视图重建属于可重试的运维动作，返回空页而不是抛异常，避免把调用方拖垮
                ShopPageDTO empty = new ShopPageDTO();
                empty.setTotal(0L);
                empty.setPages(0L);
                empty.setList(CollUtils.emptyList());
                return empty;
            }
        };
    }
}
