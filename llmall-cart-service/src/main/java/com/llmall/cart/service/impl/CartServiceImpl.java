package com.llmall.cart.service.impl;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llmall.api.client.ItemClient;
import com.llmall.api.dto.ItemDTO;
import com.llmall.cart.config.CartProperties;
import com.llmall.cart.domain.dto.CartFormDTO;
import com.llmall.cart.domain.po.Cart;
import com.llmall.cart.domain.vo.CartVO;
import com.llmall.cart.mapper.CartMapper;
import com.llmall.cart.service.ICartService;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 购物车 服务实现类（注意：不是"订单详情表"，购物车与订单是两张不同的表）
 * </p>
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    //private final IItemService itemService;
    private final ItemClient itemClient;
    private final CartProperties cartProperties;

    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();

        // 2.判断是否已经存在
        if(checkItemExists(cartFormDTO.getItemId(), userId)){
            // 2.1.存在，则更新数量
            baseMapper.updateNum(cartFormDTO.getItemId(), userId);
            return;
        }
        // 2.2.不存在，判断是否超过购物车数量
        checkCartsFull(userId);

        // 3.新增购物车条目
        // 3.1.转换PO
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        // 3.2.保存当前用户
        cart.setUserId(userId);
        // 3.3.保存到数据库
        save(cart);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        // 1.查询我的购物车列表
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId,  UserContext.getUser()).list();
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }

        // 2.转换VO
        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);

        // 3.处理VO中的商品信息
        handleCartItems(vos);

        // 4.返回
        return vos;
    }

    private void handleCartItems(List<CartVO> vos) {
        // 1.获取商品id
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
        // 2.查询商品
        //List<ItemDTO> items = itemService.queryItemByIds(itemIds);
        //OpenFeign 调用
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 3.转为 id 到 item的map
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        // 4.写入vo
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    @Override
    public void updateCartNum(Cart cart) {
        // 1.数量本身先做边界校验：负数/0 的条目会在下单时被当成"正数购买"或被静默忽略，
        //    与其让它在结算链路里继续流动，不如在入口就拦住（前端也是按 1~99 做的约束）。
        if (cart.getNum() == null || cart.getNum() < 1) {
            throw new BadRequestException("购买数量必须大于 0");
        }
        // 2.归属校验以库里的 user_id 为准，绝不能用请求体里的 userId ——
        //    请求体是前端可控的，用它做条件等于把"改谁的购物车"交给调用方决定。
        Long userId = UserContext.getUser();
        Cart exist = getById(cart.getId());
        if (exist == null || !exist.getUserId().equals(userId)) {
            // "不存在"和"不属于你"合并成同一个提示：分开说等于告诉攻击者哪些 id 是真实存在的。
            throw new ForbiddenException("购物车条目不存在或不属于当前用户");
        }
        // 3.只更新 num 一列：配合 update-strategy: not_null，未 set 的字段不会进 SQL，
        //    前端即使传了 price/itemId/userId 也落不了库；
        //    条件里再带一次 user_id，是让"越权写"在 SQL 层面也不可能发生（而不只依赖上面的判断）。
        lambdaUpdate()
                .eq(Cart::getId, exist.getId())
                .eq(Cart::getUserId, userId)
                .set(Cart::getNum, cart.getNum())
                .update();
    }

    @Override
    public void removeMyCartItem(Long id) {
        if (id == null) {
            throw new BadRequestException("购物车条目id不能为空");
        }
        Long userId = UserContext.getUser();
        Cart exist = getById(id);
        if (exist == null || !exist.getUserId().equals(userId)) {
            // 删除同样要校验归属：否则删掉别人的购物车条目只是一个 DELETE 的事，
            // 而且这类"取消类"接口很容易被误认为"删了也无所谓"而漏掉鉴权。
            throw new ForbiddenException("购物车条目不存在或不属于当前用户");
        }
        // 带 user_id 条件删除：即便上面查到的记录在并发下被改写，也不会误删他人数据。
        remove(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getId, id)
                .eq(Cart::getUserId, userId));
    }

    @Override
    public void removeByItemIds(Collection<Long> itemIds) {
        // 面向用户请求：身份取当前登录上下文
        removeByItemIds(UserContext.getUser(), itemIds);
    }

    @Override
    public void removeByItemIds(Long userId, Collection<Long> itemIds) {
        if (userId == null || CollUtils.isEmpty(itemIds)) {
            // MQ 消费端没有登录上下文时 userId 会是 null —— 这里早返回，而不是拼出
            // "user_id = null" 的条件（那会静默匹配不到任何行，是最难查的那种 bug）。
            // 告警日志由调用方（listener）打印，避免这个类再去引日志门面。
            return;
        }
        // 1.构建删除条件，userId和itemId
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<Cart>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, userId)
                .in(Cart::getItemId, itemIds);
        // 2.删除
        remove(queryWrapper);
    }

    private void checkCartsFull(Long userId) {
        // MyBatis-Plus 3.5.x 起链式 count() 返回 Long（3.4.x 是 int），类型必须跟着改
        long count = lambdaQuery().eq(Cart::getUserId, userId).count();
        if (count >= cartProperties.getMaxAmount()) {
            throw new BizIllegalException(StrUtil.format("用户购物车课程不能超过{}", cartProperties.getMaxAmount()));
        }
    }

    private boolean checkItemExists(Long itemId, Long userId) {
        // MyBatis-Plus 3.5.x 起链式 count() 返回 Long（3.4.x 是 int），类型必须跟着改
        long count = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, itemId)
                .count();
        return count > 0;
    }
}
