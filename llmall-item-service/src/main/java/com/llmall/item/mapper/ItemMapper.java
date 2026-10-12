package com.llmall.item.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.api.dto.OrderDetailDTO;
import com.llmall.item.domain.po.Item;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 商品表 Mapper 接口
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 扣减库存 + 累加销量。
     *
     * 【修复的超卖缺陷】原语句是：
     *   UPDATE item SET stock = stock - #{num} WHERE id = #{itemId}
     * 它没有 "stock >= num" 条件，并发下会把库存扣成负数（超卖）。
     * 而且原 Service 用 SqlHelper.executeBatch 的返回布尔值判断"库存不足"，
     * 那个布尔值只表示"批次是否抛异常"，并不等于"有行被更新"，因此永远拦不住超卖。
     *
     * 正确做法：把"库存是否足够"的判断交给数据库的原子更新，
     * 用受影响行数区分成功（1）与库存不足（0）。
     *
     * 【为什么还要 `#{num} > 0`】数量必须是正数，否则 `stock - num` 会退化成加库存，
     * 而 `stock >= num` 在 num 为负时恒真 —— 一个负号就能同时废掉这两道判断。
     * Service 入口已经显式拦了非法数量，这里是数据库层的兜底：
     * 只要 num <= 0，受影响行数必然是 0，调用方按"失败"处理，绝不会变成加库存。
     * 数量一律用 #{} 预编译占位符，不做任何字符串拼接（${}），杜绝注入面。
     */
    @Update("UPDATE item SET stock = stock - #{num}, sold = sold + #{num} " +
            "WHERE id = #{itemId} AND #{num} > 0 AND stock >= #{num}")
    int deductStock(OrderDetailDTO detail);

    /**
     * 回滚库存（订单取消 / 超时未支付）。
     * 销量的回退用 GREATEST 兜底，避免出现负数销量这种脏数据。
     *
     * 【原实现的致命缺陷】这条语句**完全没有数量守卫**：
     * 传 num = -N 时 `stock = stock + (-N)` 变成扣库存、`sold - (-N)` 变成加销量，
     * 一次请求就能把商品库存扣成负数并把销量刷高。
     * 现在补上 `#{num} > 0`：非法数量下受影响行数为 0，由调用方按失败处理。
     */
    @Update("UPDATE item SET stock = stock + #{num}, sold = GREATEST(sold - #{num}, 0) " +
            "WHERE id = #{itemId} AND #{num} > 0")
    int restoreStock(OrderDetailDTO detail);
}
