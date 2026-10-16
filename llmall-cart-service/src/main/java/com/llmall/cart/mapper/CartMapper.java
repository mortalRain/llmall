package com.llmall.cart.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmall.cart.domain.dto.CartFormDTO;
import com.llmall.cart.domain.po.Cart;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 购物车 Mapper 接口（表 cart）。
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
     * 【注意】不能用"先查存在→再更新/插入"替代：并发下两个请求都会查到不存在而各插一行。
     * 数量以 {@code LEAST(num + 1, #{maxNum})} 封顶，否则 num 可被刷到 int 溢出（负数）。
     * name/spec/price/image 在插入与更新两条路径上都由入参 form 覆盖，落库的是加购请求体里的快照值，
     * 不是服务端实时查询到的价格（对外展示的最新价另由 CartServiceImpl 按 itemId 回源商品域填 newPrice 字段），
     * 因此 price 只是加购时刻的留痕，结算价仍以订单侧重新计算为准。
     * SQL 不使用 MySQL 8.0.20 起废弃的 {@code VALUES(col)} 语法，
     * 全部走参数占位，5.7 与 8.x 均可用。
     *
     * @return 1=新增了一条；2=更新了已有条目；0=更新但数值没变（已达数量上限）
     */
    @Insert("INSERT INTO cart (user_id, item_id, num, name, spec, price, image, create_time, update_time) " +
            "VALUES (#{userId}, #{form.itemId}, 1, #{form.name}, #{form.spec}, #{form.price}, #{form.image}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE num = LEAST(num + 1, #{maxNum}), name = #{form.name}, spec = #{form.spec}, " +
            "price = #{form.price}, image = #{form.image}, update_time = NOW()")
    int upsertItem(@Param("userId") Long userId, @Param("form") CartFormDTO form, @Param("maxNum") Integer maxNum);
}
