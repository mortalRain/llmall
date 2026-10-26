package com.llmall.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.llmall.shop.domain.po.ShopStaff;
import com.llmall.shop.mapper.ShopStaffMapper;
import com.llmall.shop.service.impl.ShopStaffServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 门店员工判权的纯逻辑测试：核销权限判定的"最后一道门"必须 fail-closed。
 *
 * 【覆盖点】
 * 1) 参数缺失（shopId / userId 为空）→ false，且不去查库；
 * 2) 表里有该行 → true；没有 → false（不能把 null 当 true）；
 * 3) 用户任职门店列表去重，userId 为空返回空集合。
 *
 * 【为什么不用 @SpringBootTest】判权是安全逻辑，必须能在没有 MySQL/Redis/Nacos 的 CI 上跑。
 * 仓库里依赖真实中间件的用例统一标 {@code @Tag("integration")} 默认不执行，守卫类逻辑不能进那批。
 *
 * 【注意】{@code lambdaQuery()} 依赖 MyBatis-Plus 的 TableInfo 缓存把 {@code ShopStaff::getShopId}
 * 解析成列名；纯单测没有 MyBatis 启动流程，故手工初始化一次（与 MP 启动时做的事等价）。
 */
@ExtendWith(MockitoExtension.class)
class ShopStaffServiceImplTest {

    private static final long SHOP_ID = 10L;
    private static final long USER_ID = 1L;

    @Mock
    private ShopStaffMapper shopStaffMapper;

    private ShopStaffServiceImpl shopStaffService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShopStaff.class);
    }

    @BeforeEach
    void setUp() {
        shopStaffService = new ShopStaffServiceImpl();
        // ServiceImpl 的 baseMapper 正常由 @Autowired 注入，单测里直接塞 mock
        ReflectionTestUtils.setField(shopStaffService, "baseMapper", shopStaffMapper);
    }

    @Test
    @DisplayName("shopId 为空 → 非员工且不查库（fail-closed）")
    void nullShopIdIsNotStaff() {
        assertThat(shopStaffService.isStaff(null, USER_ID)).isFalse();
        verifyNoInteractions(shopStaffMapper);
    }

    @Test
    @DisplayName("userId 为空 → 非员工且不查库（fail-closed）")
    void nullUserIdIsNotStaff() {
        assertThat(shopStaffService.isStaff(SHOP_ID, null)).isFalse();
        verifyNoInteractions(shopStaffMapper);
    }

    @Test
    @DisplayName("shop_staff 命中该行 → 是员工")
    void existingRowIsStaff() {
        when(shopStaffMapper.selectCount(any())).thenReturn(1L);

        assertThat(shopStaffService.isStaff(SHOP_ID, USER_ID)).isTrue();
        verify(shopStaffMapper).selectCount(any());
    }

    @Test
    @DisplayName("shop_staff 无该行（count=0）→ 非员工")
    void missingRowIsNotStaff() {
        // 【注意】回归点：selectCount 返回 0 时必须 false，不能因为"count 不为 null"就 fail-open
        when(shopStaffMapper.selectCount(any())).thenReturn(0L);

        assertThat(shopStaffService.isStaff(SHOP_ID, USER_ID)).isFalse();
    }

    @Test
    @DisplayName("用户任职门店列表去重返回")
    void staffShopIdsAreDeduped() {
        when(shopStaffMapper.selectList(any())).thenReturn(Arrays.asList(
                new ShopStaff().setShopId(10L).setUserId(USER_ID),
                new ShopStaff().setShopId(1L).setUserId(USER_ID),
                new ShopStaff().setShopId(10L).setUserId(USER_ID)));

        List<Long> shopIds = shopStaffService.queryStaffShopIds(USER_ID);

        assertThat(shopIds).containsExactlyInAnyOrder(1L, 10L);
    }

    @Test
    @DisplayName("userId 为空 → 任职门店为空集且不查库")
    void nullUserHasNoStaffShops() {
        assertThat(shopStaffService.queryStaffShopIds(null)).isEmpty();
        verify(shopStaffMapper, never()).selectList(any());
    }
}
