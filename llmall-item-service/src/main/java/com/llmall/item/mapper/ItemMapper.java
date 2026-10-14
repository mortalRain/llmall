package com.llmall.item.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.item.domain.po.Item;
import org.apache.ibatis.annotations.Update;

/**
 * 商品表 Mapper，承载库存扣减与回滚的条件更新语句。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 扣减库存 + 累加销量。
     *
     * 【注意】"库存是否足够"由数据库原子条件更新判定：受影响行数 1 为成功，0 为库存不足或数量非法。
     * 条件里的 num > 0 不可省：数量为负时 stock - num 会退化成加库存，且 stock >= num 恒真，
     * 一个负号就能同时废掉这两道判断。
     */
    @Update("UPDATE item SET stock = stock - #{num}, sold = sold + #{num} " +
            "WHERE id = #{itemId} AND #{num} > 0 AND stock >= #{num}")
    int deductStock(OrderDetailDTO detail);

    /**
     * 回滚库存（订单取消 / 超时未支付）。
     *
     * 【注意】num > 0 是数量守卫，数量非法时受影响行数为 0，由调用方按失败处理；
     * 销量回退用 GREATEST 兜底，避免出现负数销量。
     */
    @Update("UPDATE item SET stock = stock + #{num}, sold = GREATEST(sold - #{num}, 0) " +
            "WHERE id = #{itemId} AND #{num} > 0")
    int restoreStock(OrderDetailDTO detail);
}
