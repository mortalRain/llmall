package com.llmall.shop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.llmall.common.exception.BadRequestException;
import com.llmall.common.exception.ConflictException;
import com.llmall.common.exception.ForbiddenException;
import com.llmall.common.exception.UnauthorizedException;
import com.llmall.shop.domain.po.ShopStaff;
import com.llmall.shop.domain.vo.MyShopStaffVO;
import com.llmall.shop.domain.vo.ShopStaffVO;
import com.llmall.shop.mapper.ShopStaffMapper;
import com.llmall.shop.service.impl.ShopStaffServiceImpl;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 门店员工判权 + 角色授权的纯逻辑测试：权限判定的"最后一道门"必须 fail-closed。
 *
 * 【覆盖点】
 * 1) 核销判权：参数缺失 → false 且不查库；命中 → true；无行 → false；
 * 2) STAFF 不能管理员工（列表/新增/改角色/删除全部 403）；
 * 3) MANAGER 不能授予或撤销 OWNER（否则店长可自我提权）；
 * 4) 不能删除/降级最后一个 OWNER（否则门店永久失去管理员）；
 * 5) OWNER 可新增 STAFF/MANAGER、可授予 OWNER，重复新增 409，非法角色 400；
 * 6) 我任职的门店列表带角色且按 shopId 去重。
 *
 * 【为什么不用 @SpringBootTest】判权是安全逻辑，必须能在没有 MySQL/Redis/Nacos 的 CI 上跑。
 * 仓库里依赖真实中间件的用例统一标 {@code @Tag("integration")} 默认不执行，守卫类逻辑不能进那批。
 *
 * 【注意】{@code lambdaQuery()} 依赖 MyBatis-Plus 的 TableInfo 缓存把
 * {@code ShopStaff::getShopId} 解析成列名；纯单测没有 MyBatis 启动流程，故手工初始化一次。
 */
@ExtendWith(MockitoExtension.class)
class ShopStaffServiceImplTest {

    private static final long SHOP_ID = 10L;
    private static final long OWNER_ID = 1L;
    private static final long MANAGER_ID = 3L;
    private static final long STAFF_ID = 2L;

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
        org.mockito.Mockito.lenient().when(shopStaffMapper.lockShopForStaffChange(SHOP_ID)).thenReturn(SHOP_ID);
    }

    // ------------------------------------------------------------------
    // 一、核销判权（isStaff）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("shopId 为空 → 非员工且不查库（fail-closed）")
    void nullShopIdIsNotStaff() {
        assertThat(shopStaffService.isStaff(null, OWNER_ID)).isFalse();
        verifyNoInteractions(shopStaffMapper);
    }

    @Test
    @DisplayName("userId 为空 → 非员工且不查库（fail-closed）")
    void nullUserIdIsNotStaff() {
        assertThat(shopStaffService.isStaff(SHOP_ID, null)).isFalse();
        verifyNoInteractions(shopStaffMapper);
    }

    @Test
    void missingManagementIdentityDoesNotAcquireDatabaseLocks() {
        assertThatThrownBy(() -> shopStaffService.addStaff(null, OWNER_ID, STAFF_ID, "STAFF"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> shopStaffService.removeStaff(SHOP_ID, null, STAFF_ID))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> shopStaffService.updateStaffRole(SHOP_ID, null, STAFF_ID, "STAFF"))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(shopStaffMapper);
    }

    @Test
    void missingParentShopCannotManageOrReadTargetStaff() {
        when(shopStaffMapper.lockShopForStaffChange(SHOP_ID)).thenReturn(null);
        assertThatThrownBy(() -> shopStaffService.addStaff(SHOP_ID, OWNER_ID, STAFF_ID, "STAFF"))
                .isInstanceOf(ForbiddenException.class);
        verify(shopStaffMapper, never()).selectOne(any());
        verify(shopStaffMapper, never()).insert(any(ShopStaff.class));
    }

    @Test
    @DisplayName("shop_staff 命中该行 → 是员工（任意角色都可核销）")
    void existingRowIsStaff() {
        when(shopStaffMapper.selectCount(any())).thenReturn(1L);

        assertThat(shopStaffService.isStaff(SHOP_ID, STAFF_ID)).isTrue();
        verify(shopStaffMapper).selectCount(any());
    }

    @Test
    @DisplayName("shop_staff 无该行（count=0）→ 非员工")
    void missingRowIsNotStaff() {
        // 【注意】回归点：selectCount 返回 0 时必须 false，不能因为"count 不为 null"就 fail-open
        when(shopStaffMapper.selectCount(any())).thenReturn(0L);

        assertThat(shopStaffService.isStaff(SHOP_ID, STAFF_ID)).isFalse();
    }

    // ------------------------------------------------------------------
    // 二、"我任职的门店"（角色展示）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("我任职的门店：返回 shopId + role，按 shopId 去重")
    void myStaffReturnsRoleAndDedupes() {
        when(shopStaffMapper.selectList(any())).thenReturn(Arrays.asList(
                staff(10L, OWNER_ID, "OWNER"),
                staff(1L, OWNER_ID, "MANAGER"),
                staff(10L, OWNER_ID, "OWNER")));

        List<MyShopStaffVO> rows = shopStaffService.queryMyStaff(OWNER_ID);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getShopId()).isEqualTo(1L);
        assertThat(rows.get(0).getRole()).isEqualTo("MANAGER");
        assertThat(rows.get(1).getShopId()).isEqualTo(10L);
        assertThat(rows.get(1).getRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("userId 为空 → 任职门店为空集且不查库")
    void nullUserHasNoStaffShops() {
        assertThat(shopStaffService.queryMyStaff(null)).isEmpty();
        verify(shopStaffMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 三、STAFF 不能管理员工
    // ------------------------------------------------------------------

    @Test
    @DisplayName("STAFF 查看员工列表 → 403")
    void staffCannotListStaff() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, STAFF_ID, "STAFF"));

        assertThatThrownBy(() -> shopStaffService.listStaff(SHOP_ID, STAFF_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("仅本店店主/店长");
        verify(shopStaffMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("STAFF 新增员工 → 403")
    void staffCannotAddStaff() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, STAFF_ID, "STAFF"));

        assertThatThrownBy(() -> shopStaffService.addStaff(SHOP_ID, STAFF_ID, 2L, "STAFF"))
                .isInstanceOf(ForbiddenException.class);
        verify(shopStaffMapper, never()).insert(any(ShopStaff.class));
    }

    @Test
    @DisplayName("STAFF 改角色 → 403")
    void staffCannotUpdateRole() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, STAFF_ID, "STAFF"));

        assertThatThrownBy(() -> shopStaffService.updateStaffRole(SHOP_ID, STAFF_ID, 2L, "MANAGER"))
                .isInstanceOf(ForbiddenException.class);
        verify(shopStaffMapper, never()).updateById(any(ShopStaff.class));
    }

    @Test
    @DisplayName("STAFF 删除员工 → 403（不能因为'是员工'就默认能管理）")
    void staffCannotRemoveStaff() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, STAFF_ID, "STAFF"));

        assertThatThrownBy(() -> shopStaffService.removeStaff(SHOP_ID, STAFF_ID, 2L))
                .isInstanceOf(ForbiddenException.class);
        verify(shopStaffMapper, never()).deleteById(any());
    }

    // ------------------------------------------------------------------
    // 四、MANAGER 不能授予/撤销 OWNER
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MANAGER 授予 OWNER → 403（防止店长自我提权）")
    void managerCannotGrantOwner() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, MANAGER_ID, "MANAGER"));

        assertThatThrownBy(() -> shopStaffService.addStaff(SHOP_ID, MANAGER_ID, 4L, "OWNER"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("只有店主可以授予店主");
        verify(shopStaffMapper, never()).insert(any(ShopStaff.class));
    }

    @Test
    @DisplayName("MANAGER 把 OWNER 降级 → 403（撤销店主同样只有店主能做）")
    void managerCannotRevokeOwner() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, MANAGER_ID, "MANAGER"), staff(SHOP_ID, OWNER_ID, "OWNER"));

        assertThatThrownBy(() -> shopStaffService.updateStaffRole(SHOP_ID, MANAGER_ID, OWNER_ID, "STAFF"))
                .isInstanceOf(ForbiddenException.class);
        verify(shopStaffMapper, never()).updateById(any(ShopStaff.class));
    }

    @Test
    @DisplayName("MANAGER 删除 OWNER → 403")
    void managerCannotRemoveOwner() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, MANAGER_ID, "MANAGER"), staff(SHOP_ID, OWNER_ID, "OWNER"));

        assertThatThrownBy(() -> shopStaffService.removeStaff(SHOP_ID, MANAGER_ID, OWNER_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(shopStaffMapper, never()).deleteById(any());
    }

    // ------------------------------------------------------------------
    // 五、最后一个 OWNER 保护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除最后一个 OWNER → 409（包括店主删自己）")
    void cannotRemoveLastOwner() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), staff(SHOP_ID, OWNER_ID, "OWNER"));
        when(shopStaffMapper.selectList(any())).thenReturn(List.of(staff(SHOP_ID, OWNER_ID, "OWNER")));

        assertThatThrownBy(() -> shopStaffService.removeStaff(SHOP_ID, OWNER_ID, OWNER_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("最后一个店主");
        verify(shopStaffMapper, never()).deleteById(any());
    }

    @Test
    @DisplayName("降级最后一个 OWNER → 409（否则门店永久失去管理员）")
    void cannotDemoteLastOwner() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), staff(SHOP_ID, OWNER_ID, "OWNER"));
        when(shopStaffMapper.selectList(any())).thenReturn(List.of(staff(SHOP_ID, OWNER_ID, "OWNER")));

        assertThatThrownBy(() -> shopStaffService.updateStaffRole(SHOP_ID, OWNER_ID, OWNER_ID, "MANAGER"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("最后一个店主");
        verify(shopStaffMapper, never()).updateById(any(ShopStaff.class));
    }

    @Test
    @DisplayName("还有第二个 OWNER 时，可以撤销其中一个")
    void canDemoteOwnerWhenAnotherExists() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), staff(SHOP_ID, 4L, "OWNER"));
        when(shopStaffMapper.selectList(any())).thenReturn(List.of(
                staff(SHOP_ID, OWNER_ID, "OWNER"), staff(SHOP_ID, 4L, "OWNER")));
        when(shopStaffMapper.updateById(any(ShopStaff.class))).thenReturn(1);

        shopStaffService.updateStaffRole(SHOP_ID, OWNER_ID, 4L, "MANAGER");

        ArgumentCaptor<ShopStaff> captor = ArgumentCaptor.forClass(ShopStaff.class);
        verify(shopStaffMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("MANAGER");
    }

    // ------------------------------------------------------------------
    // 六、OWNER/MANAGER 的正常路径与参数校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("OWNER 新增 STAFF → 落库角色为 STAFF")
    void ownerCanAddStaff() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), null);
        when(shopStaffMapper.insert(any(ShopStaff.class))).thenReturn(1);

        shopStaffService.addStaff(SHOP_ID, OWNER_ID, STAFF_ID, "staff");

        ArgumentCaptor<ShopStaff> captor = ArgumentCaptor.forClass(ShopStaff.class);
        verify(shopStaffMapper).insert(captor.capture());
        assertThat(captor.getValue().getShopId()).isEqualTo(SHOP_ID);
        assertThat(captor.getValue().getUserId()).isEqualTo(STAFF_ID);
        // 请求体小写也归一化成大写，存量查询/前端展示口径一致
        assertThat(captor.getValue().getRole()).isEqualTo("STAFF");
    }

    @Test
    @DisplayName("OWNER 授予 OWNER → 允许（店长不行，店主可以）")
    void ownerCanGrantOwner() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), null);
        when(shopStaffMapper.insert(any(ShopStaff.class))).thenReturn(1);

        shopStaffService.addStaff(SHOP_ID, OWNER_ID, 4L, "OWNER");

        ArgumentCaptor<ShopStaff> captor = ArgumentCaptor.forClass(ShopStaff.class);
        verify(shopStaffMapper).insert(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("MANAGER 新增 STAFF → 允许（店长可管理普通员工）")
    void managerCanAddStaff() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, MANAGER_ID, "MANAGER"), null);
        when(shopStaffMapper.insert(any(ShopStaff.class))).thenReturn(1);

        shopStaffService.addStaff(SHOP_ID, MANAGER_ID, STAFF_ID, "STAFF");

        verify(shopStaffMapper).insert(any(ShopStaff.class));
    }

    @Test
    @DisplayName("重复新增同一员工 → 409")
    void duplicateStaffIsConflict() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), staff(SHOP_ID, STAFF_ID, "STAFF"));

        assertThatThrownBy(() -> shopStaffService.addStaff(SHOP_ID, OWNER_ID, STAFF_ID, "STAFF"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已是本店员工");
        verify(shopStaffMapper, never()).insert(any(ShopStaff.class));
    }

    @Test
    @DisplayName("role 非法 → 400（不能静默当成 STAFF）")
    void invalidRoleIsBadRequest() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"));

        assertThatThrownBy(() -> shopStaffService.addStaff(SHOP_ID, OWNER_ID, 4L, "BOSS"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("role 取值不合法");
    }

    @Test
    @DisplayName("OWNER 删除 STAFF → 允许")
    void ownerCanRemoveStaff() {
        when(shopStaffMapper.selectOne(any()))
                .thenReturn(staff(SHOP_ID, OWNER_ID, "OWNER"), staff(SHOP_ID, STAFF_ID, "STAFF"));
        when(shopStaffMapper.deleteById(anyLong())).thenReturn(1);

        shopStaffService.removeStaff(SHOP_ID, OWNER_ID, STAFF_ID);

        verify(shopStaffMapper).deleteById(anyLong());
    }

    @Test
    @DisplayName("MANAGER 查看员工列表 → 按角色排序（OWNER 在前）")
    void managerCanListStaffSortedByRole() {
        when(shopStaffMapper.selectOne(any())).thenReturn(staff(SHOP_ID, MANAGER_ID, "MANAGER"));
        when(shopStaffMapper.selectList(any())).thenReturn(Arrays.asList(
                staff(SHOP_ID, STAFF_ID, "STAFF"),
                staff(SHOP_ID, OWNER_ID, "OWNER"),
                staff(SHOP_ID, MANAGER_ID, "MANAGER")));

        List<ShopStaffVO> rows = shopStaffService.listStaff(SHOP_ID, MANAGER_ID);

        assertThat(rows).extracting(ShopStaffVO::getRole)
                .containsExactly("OWNER", "MANAGER", "STAFF");
        verify(shopStaffMapper, never()).lockShopForStaffChange(anyLong());
    }

    private ShopStaff staff(Long shopId, Long userId, String role) {
        return new ShopStaff()
                .setId(userId)
                .setShopId(shopId)
                .setUserId(userId)
                .setRole(role)
                .setCreateTime(LocalDateTime.now());
    }
}
