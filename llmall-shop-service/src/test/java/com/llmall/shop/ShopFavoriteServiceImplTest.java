package com.llmall.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.llmall.api.dto.ShopDTO;
import com.llmall.common.exception.BizIllegalException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.shop.domain.po.Shop;
import com.llmall.shop.domain.po.ShopFavorite;
import com.llmall.shop.mapper.ShopFavoriteMapper;
import com.llmall.shop.service.IShopService;
import com.llmall.shop.service.impl.ShopFavoriteServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 门店收藏的纯逻辑测试（不依赖 MySQL/Redis/Nacos，可在 CI 无中间件环境执行）。
 *
 * 【覆盖点】
 * 1) 重复收藏幂等：已存在时不再 insert、不抛异常；
 * 2) 取消不存在的收藏也成功：delete 影响 0 行不是错误；
 * 3) 收藏列表只含本人：查询条件必须带 user_id = 当前登录人，且返回顺序按收藏时间倒序；
 * 4) 未登录 fail-closed：不查库、直接 401；
 * 5) 收藏不存在的门店：400，不产生关系行。
 *
 * 【注意】{@code lambdaQuery()} 依赖 MyBatis-Plus 的 TableInfo 缓存把
 * {@code ShopFavorite::getUserId} 解析成列名；纯单测没有 MyBatis 启动流程，故手工初始化一次。
 */
@ExtendWith(MockitoExtension.class)
class ShopFavoriteServiceImplTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final long SHOP_ID = 10L;

    @Mock
    private ShopFavoriteMapper shopFavoriteMapper;

    @Mock
    private IShopService shopService;

    private ShopFavoriteServiceImpl favoriteService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShopFavorite.class);
    }

    @BeforeEach
    void setUp() {
        favoriteService = new ShopFavoriteServiceImpl(shopService);
        // ServiceImpl 的 baseMapper 正常由 @Autowired 注入，单测里直接塞 mock
        ReflectionTestUtils.setField(favoriteService, "baseMapper", shopFavoriteMapper);
    }

    // ------------------------------------------------------------------
    // 一、收藏幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重复收藏 → 成功且不再 insert（幂等）")
    void duplicateFavoriteIsIdempotent() {
        when(shopService.getById(SHOP_ID)).thenReturn(new Shop().setId(SHOP_ID));
        when(shopFavoriteMapper.selectCount(any())).thenReturn(1L);

        // 不抛异常即成功；重复收藏不能变成 409
        favoriteService.addFavorite(SHOP_ID, USER_ID);

        verify(shopFavoriteMapper, never()).insert(any(ShopFavorite.class));
    }

    @Test
    @DisplayName("首次收藏 → 落库 userId/shopId 正确")
    void firstFavoriteInsertsRow() {
        when(shopService.getById(SHOP_ID)).thenReturn(new Shop().setId(SHOP_ID));
        when(shopFavoriteMapper.selectCount(any())).thenReturn(0L);
        when(shopFavoriteMapper.insert(any(ShopFavorite.class))).thenReturn(1);

        favoriteService.addFavorite(SHOP_ID, USER_ID);

        ArgumentCaptor<ShopFavorite> captor = ArgumentCaptor.forClass(ShopFavorite.class);
        verify(shopFavoriteMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getShopId()).isEqualTo(SHOP_ID);
    }

    @Test
    @DisplayName("收藏不存在的门店 → 400 且不写关系行")
    void favoriteMissingShopIsBadRequest() {
        when(shopService.getById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> favoriteService.addFavorite(SHOP_ID, USER_ID))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("门店不存在");
        verify(shopFavoriteMapper, never()).insert(any(ShopFavorite.class));
    }

    @Test
    @DisplayName("未登录收藏 → 401 且不查门店/不写库（fail-closed）")
    void anonymousFavoriteIsUnauthorized() {
        assertThatThrownBy(() -> favoriteService.addFavorite(SHOP_ID, null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("未登录");
        verifyNoInteractions(shopService, shopFavoriteMapper);
    }

    // ------------------------------------------------------------------
    // 二、取消收藏幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("取消不存在的收藏 → 成功（delete 0 行不是错误）")
    void removingMissingFavoriteIsIdempotent() {
        // 不 stub delete：Mockito 对 int 返回值默认 0，正好模拟"本来就没收藏"
        favoriteService.removeFavorite(SHOP_ID, USER_ID);

        ArgumentCaptor<Wrapper<ShopFavorite>> captor = wrapperCaptor();
        verify(shopFavoriteMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id");
    }

    @Test
    @DisplayName("取消收藏只删自己的那一行 → 条件同时带 user_id 与 shop_id")
    void removeFavoriteFiltersByOwnerAndShop() {
        favoriteService.removeFavorite(SHOP_ID, USER_ID);

        ArgumentCaptor<Wrapper<ShopFavorite>> captor = wrapperCaptor();
        verify(shopFavoriteMapper).delete(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        // 【注意】MP 的 SQL 片段与参数表是懒生成的：先取 getSqlSegment() 才会填充 paramNameValuePairs
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("user_id").contains("shop_id");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(USER_ID, SHOP_ID)
                .doesNotContain(OTHER_USER_ID);
    }

    @Test
    @DisplayName("未登录取消收藏 → 401 且不写库")
    void anonymousRemoveIsUnauthorized() {
        assertThatThrownBy(() -> favoriteService.removeFavorite(SHOP_ID, null))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(shopFavoriteMapper);
    }

    // ------------------------------------------------------------------
    // 三、收藏列表只含本人
    // ------------------------------------------------------------------

    @Test
    @DisplayName("我的收藏：查询只按当前 user_id，按收藏时间倒序返回且跳过已删门店")
    void myFavoritesOnlyCurrentUserAndKeepOrder() {
        when(shopFavoriteMapper.selectList(any())).thenReturn(Arrays.asList(
                favorite(1L, SHOP_ID, 3),
                favorite(2L, 1L, 2),
                favorite(3L, 404L, 1)));
        when(shopService.queryByIds(any())).thenReturn(Arrays.asList(
                shop(1L, "门店一"),
                shop(SHOP_ID, "门店十")));

        List<ShopDTO> result = favoriteService.listMyFavorites(USER_ID);

        assertThat(result).extracting(ShopDTO::getId).containsExactly(SHOP_ID, 1L);
        assertThat(result).extracting(ShopDTO::getName).containsExactly("门店十", "门店一");

        ArgumentCaptor<Wrapper<ShopFavorite>> captor = wrapperCaptor();
        verify(shopFavoriteMapper).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        // 【注意】先取 SQL 片段，MP 才会把 eq 的值写进 paramNameValuePairs
        // 列表查询只按 user_id 过滤；再带 shop_id 会把"我的收藏"变成"我收藏的某一家"
        assertThat(wrapper.getSqlSegment()).contains("user_id").doesNotContain("shop_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(USER_ID);
    }

    @Test
    @DisplayName("我的收藏：空列表直接返回空集，不查门店")
    void emptyFavoritesDoesNotQueryShops() {
        when(shopFavoriteMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThat(favoriteService.listMyFavorites(USER_ID)).isEmpty();
        verify(shopService, never()).queryByIds(any());
    }

    @Test
    @DisplayName("未登录查收藏列表 → 空集且不查库")
    void anonymousFavoritesIsEmpty() {
        assertThat(favoriteService.listMyFavorites(null)).isEmpty();
        verifyNoInteractions(shopFavoriteMapper, shopService);
    }

    // ------------------------------------------------------------------
    // 四、是否已收藏
    // ------------------------------------------------------------------

    @Test
    @DisplayName("是否已收藏：缺参数按未收藏且不查库（fail-closed）")
    void favoriteStatusWithNullIsFalse() {
        assertThat(favoriteService.isFavorite(null, USER_ID)).isFalse();
        assertThat(favoriteService.isFavorite(SHOP_ID, null)).isFalse();
        verifyNoInteractions(shopFavoriteMapper);
    }

    private ShopFavorite favorite(Long id, Long shopId, int daysAgo) {
        return new ShopFavorite()
                .setId(id)
                .setUserId(USER_ID)
                .setShopId(shopId)
                .setCreateTime(LocalDateTime.now().minusDays(daysAgo));
    }

    private ShopDTO shop(Long id, String name) {
        ShopDTO dto = new ShopDTO();
        dto.setId(id);
        dto.setName(name);
        return dto;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<ShopFavorite>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class) Wrapper.class);
    }
}
