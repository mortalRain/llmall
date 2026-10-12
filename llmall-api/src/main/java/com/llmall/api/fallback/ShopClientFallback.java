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
                // 【重要】对账类查询绝不能"猜一个答案"返回。
                // 原实现返回 true（本意是"别误判成缺失、别重复补偿"），但调用方拿到 true
                // 就认为"门店已处理"，于是 llmall-shop-service 挂掉时对账会打印"下游均已处理" ——
                // 一致性缺口被伪装成正常，"可重试的未知"变成了"确定的已处理"。
                // 抛异常让调用方自己区分三态（已消费/未消费/未知）：未知不计入"均已处理"，
                // 保留待下轮重试，必要时告警人工介入。
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
