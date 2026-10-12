package com.llmall.cart.service;
import com.baomidou.mybatisplus.extension.service.IService;
import com.llmall.cart.domain.dto.CartFormDTO;
import com.llmall.cart.domain.po.Cart;
import com.llmall.cart.domain.vo.CartVO;
import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 订单详情表 服务类
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
public interface ICartService extends IService<Cart> {

    void addItem2Cart(CartFormDTO cartFormDTO);

    List<CartVO> queryMyCarts();

    /**
     * 修改购物车条目的购买数量。
     *
     * 为什么不直接暴露 {@code updateById(Cart)}：那样前端传来的 userId / itemId / price
     * 都会参与 UPDATE，改一下 id 就能改别人的购物车、改一下 price 就能改"结算单价"。
     * 数量修改必须收敛成"按 id 找自己的条目，只改 num"这一件事。
     */
    void updateCartNum(Cart cart);

    /**
     * 删除购物车条目（仅限当前登录用户自己的条目）。
     */
    void removeMyCartItem(Long id);

    void removeByItemIds(Collection<Long> itemIds);

    /**
     * 按 userId + 商品 id 清理购物车（供 MQ 消费端使用）。
     * MQ 监听线程没有登录上下文，身份必须由消息显式传入，不能依赖 UserContext。
     */
    void removeByItemIds(Long userId, Collection<Long> itemIds);
}
