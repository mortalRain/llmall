package com.llmall.cart.service.impl;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.api.client.ItemClient;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.api.dto.ItemDTO;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.config.CartProperties;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.constant.CartRedisConstants;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.constant.CartRedisScripts;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.domain.dto.CartFormDTO;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.domain.po.Cart;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.domain.vo.CartVO;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.mapper.CartMapper;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.cart.service.ICartService;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.utils.BeanUtils;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.utils.CollUtils;
import com.llmall.common.tx.TxAfterCommit;
import com.llmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 购物车业务：加购、改数量、删条目、下单后清空购物车，Redis Hash 缓存 + MySQL 双写。
 *
 * 【注意】读缓存"未命中"与"购物车为空"是两种状态，混同会在 Redis 抖动时把用户的购物车显示成空。
 * 【注意】写路径先写 DB 再整份重建缓存，且重建必须等事务提交；缓存读写失败只记日志，不抛给业务。
 * 【注意】不做单字段删除的精细更新：整份重建保证"缓存 == 那一刻的 DB"，条目数有硬上限（默认 10），成本可忽略。
 *
 * @author 同城生活
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    /** 单个商品加购数量上限：不封顶时 num 会一直加到 int 溢出变负数，下游无法解释 */
    private static final int MAX_NUM_PER_ITEM = 99;

    /** 购物车条目数上限的兜底值：配置缺失或非法时使用，与 CartProperties 的默认值保持一致 */
    private static final int DEFAULT_MAX_ENTRIES = 10;

    private final ItemClient itemClient;
    private final CartProperties cartProperties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }

        // 一次原子 upsert 完成"有则加数量、无则插入"：依赖唯一键 uk_user_item_id +
        // ON DUPLICATE KEY UPDATE，"先查后写"在并发连点下会插入两行同样的商品
        Integer maxAmount = cartProperties.getMaxAmount();
        int maxEntries = (maxAmount == null || maxAmount <= 0) ? DEFAULT_MAX_ENTRIES : maxAmount;
        int affected = baseMapper.upsertItem(userId, cartFormDTO, MAX_NUM_PER_ITEM);

        // 只有新增条目（affected=1）才校验条目数上限：更新已有条目不增加种类数，
        // 不该因为购物车已满而拒绝加数量
        if (affected == 1) {
            long count = lambdaQuery().eq(Cart::getUserId, userId).count();
            if (count > maxEntries) {
                // 超限则撤掉刚插入的条目，维持"最多 maxEntries 种商品"
                lambdaUpdate().eq(Cart::getUserId, userId).eq(Cart::getItemId, cartFormDTO.getItemId()).remove();
                // 撤销后 DB 已无此条目，缓存必须紧随重建，否则缓存里会留下刚插入的条目
                rebuildCacheAfterCommit(userId);
                throw new BizIllegalException(StrUtil.format("购物车最多放 {} 种商品", maxEntries));
            }
        }
        // DB 已是最新，整份重建缓存（提交后执行，见 rebuildCacheAfterCommit）
        rebuildCacheAfterCommit(userId);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new UnauthorizedException("未登录");
        }

        // 先走缓存：未命中（readCache 返回 null）要回源 DB，不能当成空购物车
        List<Cart> carts = readCache(userId);
        if (carts == null) {
            carts = lambdaQuery().eq(Cart::getUserId, userId).list();
            // 空车也回填（否则空车用户每次刷新都打一次 DB）：由 TTL 兜底回收，不会长期驻留
            writeCache(userId, carts);
        }
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }

        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);

        handleCartItems(vos);

        return vos;
    }

    private void handleCartItems(List<CartVO> vos) {
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (CollUtils.isEmpty(items)) {
            return;
        }
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
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
        // 数量边界在入口拦住：负数/0 会在下单时被当成"正数购买"或被静默忽略（前端约束为 1~99）
        if (cart.getNum() == null || cart.getNum() < 1) {
            throw new BadRequestException("购买数量必须大于 0");
        }
        // 归属校验以库里的 user_id 为准：请求体的 userId 前端可控，
        // 用它做条件等于把"改谁的购物车"交给调用方决定
        Long userId = UserContext.getUser();
        Cart exist = getById(cart.getId());
        if (exist == null || !exist.getUserId().equals(userId)) {
            // "不存在"与"不属于你"合并为同一提示，分开说会暴露哪些 id 真实存在
            throw new ForbiddenException("购物车条目不存在或不属于当前用户");
        }
        // 只更新 num 一列：配合 update-strategy: not_null，未 set 的字段不进 SQL；
        // 条件再带一次 user_id，让越权写在 SQL 层面也不可能发生
        lambdaUpdate()
                .eq(Cart::getId, exist.getId())
                .eq(Cart::getUserId, userId)
                .set(Cart::getNum, cart.getNum())
                .update();
        rebuildCacheAfterCommit(userId);
    }

    @Override
    public void removeMyCartItem(Long id) {
        if (id == null) {
            throw new BadRequestException("购物车条目id不能为空");
        }
        Long userId = UserContext.getUser();
        Cart exist = getById(id);
        if (exist == null || !exist.getUserId().equals(userId)) {
            // 删除同样要校验归属："取消类"接口容易被当作"删了也无妨"而漏掉鉴权
            throw new ForbiddenException("购物车条目不存在或不属于当前用户");
        }
        // 带 user_id 条件删除：即使上面查到的记录在并发下被改写，也不会误删他人数据
        remove(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getId, id)
                .eq(Cart::getUserId, userId));
        // 只删 DB 不重建缓存，用户刷新后仍会看到旧条目（最长 CART_CACHE_TTL）
        rebuildCacheAfterCommit(userId);
    }

    @Override
    public void removeByItemIds(Collection<Long> itemIds) {
        removeByItemIds(UserContext.getUser(), itemIds);
    }

    @Override
    public void removeByItemIds(Long userId, Collection<Long> itemIds) {
        if (userId == null || CollUtils.isEmpty(itemIds)) {
            // MQ 消费端无登录上下文时 userId 为 null：早返回，避免拼出 "user_id = null"
            // 的条件而静默匹配不到任何行
            return;
        }
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<Cart>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, userId)
                .in(Cart::getItemId, itemIds);
        remove(queryWrapper);
        // 下单后清空购物车也走这条路：不重建缓存会表现为"下单了购物车还在"
        rebuildCacheAfterCommit(userId);
    }

    /**
     * 读缓存：命中返回条目列表，未命中返回 {@code null}（不是空列表）。
     *
     * 【注意】空购物车（新用户、下单后清空）是合法状态，必须能缓存下来；与"key 不存在"混同
     * 会导致 Redis 一重启所有用户的购物车都显示为空。
     * 反序列化失败按未命中处理并丢弃脏值：缓存格式变更或数据被手工改过时，
     * 宁可回源一次 DB，也不让购物车接口持续 500。
     */
    private List<Cart> readCache(Long userId) {
        String key = CartRedisConstants.CART_CACHE_KEY + userId;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            List<Cart> carts = new ArrayList<>(entries.size());
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                // 空车占位字段（撑住 key 以缓存空车、防穿透）不是真实条目，必须跳过，
                // 否则会多出一条 itemId 为 null 的假条目
                if (CartRedisConstants.EMPTY_CART_MARKER.equals(String.valueOf(entry.getKey()))) {
                    continue;
                }
                try {
                    carts.add(objectMapper.readValue(String.valueOf(entry.getValue()), Cart.class));
                } catch (Exception e) {
                    log.warn("购物车缓存条目反序列化失败，丢弃脏值并回源 DB: userId={}", userId, e);
                    deleteCache(key);
                    return null;
                }
            }
            return carts;
        } catch (Exception e) {
            // 缓存读失败按未命中回源 DB：购物车是核心链路，不能让 Redis 抖动影响可用性
            log.warn("读取购物车缓存失败，降级为回源 DB: userId={}", userId, e);
            return null;
        }
    }

    /** 整份回填缓存；失败只记日志：缓存是可选加速层，不该把业务带崩 */
    private void writeCache(Long userId, List<Cart> carts) {
        String key = CartRedisConstants.CART_CACHE_KEY + userId;
        try {
            rebuildCache(key, carts);
        } catch (Exception e) {
            log.error("重建购物车缓存失败（DB 已是最新，下次读会回源）: userId={}", userId, e);
        }
    }

    /**
     * 事务提交后重建缓存；无事务上下文（如 MQ 消费端直接调用）时立即重建。
     *
     * 【注意】在事务内重建等于把尚未提交（很可能随后回滚）的状态写进 Redis，回滚后 DB 是旧值、
     * 缓存却是新值；提交后重建最坏只是缓存短暂落后于 DB，由 TTL 兜底。
     */
    private void rebuildCacheAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        TxAfterCommit.run("rebuild cart cache user=" + userId, () -> rebuildFromDb(userId));
    }

    /** 从 DB 重新读一遍整份重建缓存：以"库里那一刻的样子"为准，不猜哪个字段变了 */
    private void rebuildFromDb(Long userId) {
        try {
            List<Cart> latest = lambdaQuery().eq(Cart::getUserId, userId).list();
            rebuildCache(CartRedisConstants.CART_CACHE_KEY + userId, latest);
        } catch (Exception e) {
            // 重建失败不影响主流程：缓存有 TTL，到期后下次读自然回源
            log.error("按 DB 重建购物车缓存失败: userId={}", userId, e);
        }
    }

    /**
     * 真正写 Redis：一条 Lua 完成 {@code DEL + HSET(全部字段) + EXPIRE}，不能拆成三条命令
     * （原因见 {@link CartRedisScripts#CART_REBUILD}）。
     */
    private void rebuildCache(String key, List<Cart> carts) throws Exception {
        String[] args = new String[1 + (carts == null ? 0 : carts.size() * 2)];
        args[0] = String.valueOf(CartRedisConstants.CART_CACHE_TTL.getSeconds());
        int i = 1;
        if (carts != null) {
            for (Cart cart : carts) {
                if (cart.getItemId() == null) {
                    continue;
                }
                args[i++] = String.valueOf(cart.getItemId());
                args[i++] = objectMapper.writeValueAsString(cart);
            }
        }
        // 跳过 itemId 为 null 的行会多留空位，裁掉尾部空串（脚本按 ARGV 成对读取）
        int used = i;
        String[] compact = new String[used];
        System.arraycopy(args, 0, compact, 0, used);
        redisTemplate.execute(CartRedisScripts.CART_REBUILD,
                Collections.singletonList(key), (Object[]) compact);
    }

    /** 删缓存（仅用于清掉反序列化失败的脏值；正常路径一律走重建） */
    private void deleteCache(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除购物车缓存失败: key={}", key, e);
        }
    }
}
