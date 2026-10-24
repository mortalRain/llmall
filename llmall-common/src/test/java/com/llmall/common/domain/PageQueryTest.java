package com.llmall.common.domain;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数收敛与排序白名单的单元测试。
 *
 * 【为什么值得测】分页参数直接来自前端，非法值曾被 MyBatis-Plus 解释成"不分页"，
 * 等于一次请求拉走整张表；排序字段则会被拼进 ORDER BY，是注入面的重点。
 */
class PageQueryTest {

    @Test
    void pageNumberIsNormalized() {
        assertThat(PageQuery.normalizePageNo(null)).isEqualTo(1);
        assertThat(PageQuery.normalizePageNo(0)).isEqualTo(1);
        assertThat(PageQuery.normalizePageNo(-5)).isEqualTo(1);
        assertThat(PageQuery.normalizePageNo(3)).isEqualTo(3);
    }

    @Test
    void pageSizeIsNormalizedAndCapped() {
        assertThat(PageQuery.normalizeSize(null)).isEqualTo(20);
        assertThat(PageQuery.normalizeSize(0)).isEqualTo(20);
        assertThat(PageQuery.normalizeSize(-1)).isEqualTo(20);
        assertThat(PageQuery.normalizeSize(10_000)).isEqualTo(PageQuery.getMaxPageSize());
        assertThat(PageQuery.normalizeSize(30)).isEqualTo(30);
    }

    @Test
    void perEndpointCapNeverExceedsGlobalCap() {
        int globalCap = PageQuery.getMaxPageSize();
        assertThat(PageQuery.normalizeSize(500, 10, globalCap * 10)).isEqualTo(globalCap);
    }

    @Test
    void sortColumnWhitelistRejectsInjection() {
        PageQuery query = new PageQuery();
        query.setSortBy("price; drop table t");
        assertThat(query.safeSortBy()).isNull();
        query.setSortBy("price desc");
        assertThat(query.safeSortBy()).isNull();
        query.setSortBy("1price");
        assertThat(query.safeSortBy()).isNull();
        query.setSortBy("price");
        assertThat(query.safeSortBy()).isEqualTo("price");
        query.setSortBy(" create_time ");
        assertThat(query.safeSortBy()).isEqualTo("create_time");
    }

    @Test
    void mpPageUsesNormalizedValuesAndDefaultOrder() {
        PageQuery query = new PageQuery();
        query.setPageNo(0);
        query.setPageSize(1000);
        query.setSortBy("bad column");
        Page<Object> page = query.toMpPage("id", false);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(PageQuery.getMaxPageSize());
        assertThat(page.orders()).hasSize(1);
        assertThat(page.orders().get(0).getColumn()).isEqualTo("id");
        assertThat(page.orders().get(0).isAsc()).isFalse();
    }

    @Test
    void fromUsesSameNormalizationAsPage() {
        PageQuery query = new PageQuery();
        query.setPageNo(2);
        query.setPageSize(1000);
        assertThat(query.from()).isEqualTo(PageQuery.getMaxPageSize());
    }
}
