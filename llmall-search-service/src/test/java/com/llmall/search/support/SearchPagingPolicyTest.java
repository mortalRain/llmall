package com.llmall.search.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索深分页边界的单元测试。
 */
class SearchPagingPolicyTest {

    @Test
    void firstPageIsAlwaysAllowed() {
        assertThat(SearchPagingPolicy.exceedsWindow(1, 50)).isFalse();
        assertThat(SearchPagingPolicy.exceedsWindow(1, 1)).isFalse();
    }

    @Test
    void lastPageInsideWindowIsAllowed() {
        // (200 - 1) * 50 + 50 = 10000，恰好等于上限
        assertThat(SearchPagingPolicy.exceedsWindow(200, 50)).isFalse();
    }

    @Test
    void firstPageBeyondWindowIsRejected() {
        // (201 - 1) * 50 + 50 = 10050
        assertThat(SearchPagingPolicy.exceedsWindow(201, 50)).isTrue();
    }

    @Test
    void tenPerPageBoundary() {
        assertThat(SearchPagingPolicy.exceedsWindow(1000, 10)).isFalse();
        assertThat(SearchPagingPolicy.exceedsWindow(1001, 10)).isTrue();
    }

    @Test
    void illegalInputDoesNotThrow() {
        assertThat(SearchPagingPolicy.exceedsWindow(0, 10)).isFalse();
        assertThat(SearchPagingPolicy.exceedsWindow(-1, 10)).isFalse();
        assertThat(SearchPagingPolicy.exceedsWindow(1, 0)).isFalse();
    }
}
