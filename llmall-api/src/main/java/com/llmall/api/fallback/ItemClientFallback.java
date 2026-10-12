package com.llmall.api.fallback;

import com.llmall.api.client.ItemClient;
import com.llmall.api.dto.ItemDTO;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.utils.CollUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Slf4j
@Component
public class ItemClientFallback implements FallbackFactory<ItemClient> {
    @Override
    public ItemClient create(Throwable cause) { //形参cause为异常信息
        return new ItemClient() {
            /**
             * 调用queryItemByIds出异常或限流会调用这个方法
             * @param ids
             * @return
             */
            @Override
            public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
                log.error("远程调用ItemClient的queryItemByIds方法出现异常，参数：{}", ids, cause);
                //查询购物车允许失败，查询失败，返回空集合
                return CollUtils.emptyList();
            }

            /**
             * 调用deductStock出异常或限流会调用这个方法
             * @param items
             */
            @Override
            public void deductStock(List<OrderDetailDTO> items) {
                // 库存扣减业务需要触发事务回滚，查询失败，抛出异常
                throw new BizIllegalException(cause);
            }

            /**
             * 调用restoreStock出异常或限流会调用这个方法
             * @param items
             */
            @Override
            public void restoreStock(List<OrderDetailDTO> items) {
                // 库存回滚同样必须让调用方感知（订单取消失败会导致库存永久少卖），不能静默降级
                log.error("远程调用ItemClient的restoreStock方法出现异常，参数：{}", items, cause);
                throw new BizIllegalException(cause);
            }
        };
    }
}