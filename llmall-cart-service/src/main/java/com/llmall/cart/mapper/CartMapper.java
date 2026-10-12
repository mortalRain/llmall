package com.llmall.cart.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.cart.domain.dto.CartFormDTO;
import com.llmall.cart.domain.po.Cart;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 购物车 Mapper 接口（表 cart，与订单详情表无关 —— 老注释写错了，这里更正）
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface CartMapper extends BaseMapper<Cart> {

    @Update("UPDATE cart SET num = num + 1 WHERE user_id = #{userId} AND item_id = #{itemId} AND num < #{maxNum}")
    void updateNum(@Param("itemId") Long itemId, @Param("userId") Long userId, @Param("maxNum") Integer maxNum);

    /**
     * 加购（原子 upsert）：靠唯一键 {@code uk_user_item_id(user_id, item_id)} 保证幂等。
     *
     * <p>为什么必须用 upsert 而不是"先查存在→再更新/插入"：后者在并发下必然出问题 ——
     * 两个请求同时查到"不存在"，就会插入两行同样的商品；用户连点两次"加入购物车"
     * 是完全正常的操作，不是攻击。有了唯一键 + ON DUPLICATE KEY UPDATE，
     * 并发最终一定收敛成一行，且没有任何窗口期。
     *
     * <p>数量用 {@code LEAST(num + 1, #{maxNum})} 封顶：不封顶时 num 可以被刷到
     * int 溢出（负数），下单时会出现"负数量"这种下游无法解释的数据。
     * 商品名称/规格/价格/图片在插入与更新时都会刷新 —— 商品改价后购物车展示的应是新价，
     * 而不是加购那一刻的旧价（结算价最终以订单侧重新计算为准，这里只影响展示）。
     *
     * <p>SQL 里不使用 MySQL 8.0.20 起已废弃的 {@code VALUES(col)} 语法，全部走参数占位，
     * 因此 5.7 与 8.x 都能用。
     *
     * @return 1=新增了一条；2=更新了已有条目；0=更新但数值没变（已达数量上限）
     */
    @Insert("INSERT INTO cart (user_id, item_id, num, name, spec, price, image, create_time, update_time) " +
            "VALUES (#{userId}, #{form.itemId}, 1, #{form.name}, #{form.spec}, #{form.price}, #{form.image}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE num = LEAST(num + 1, #{maxNum}), name = #{form.name}, spec = #{form.spec}, " +
            "price = #{form.price}, image = #{form.image}, update_time = NOW()")
    int upsertItem(@Param("userId") Long userId, @Param("form") CartFormDTO form, @Param("maxNum") Integer maxNum);
}
