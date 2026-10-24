package com.llmall.search.support;

/**
 * 检索分页的边界策略。
 *
 * 【为什么单独抽出来】上限值同时被入口校验与单元测试引用；
 * 更重要的是"为什么是 1 万"需要一处可读的说明，而不是散落在方法体里的魔法数字。
 */
public final class SearchPagingPolicy {

    /**
     * ES 默认的 index.max_result_window。
     *
     * 【注意】from + size 超过该值时 ES 会抛错，而 auto 模式会把该错误当成"ES 不可用"
     * 回落 MySQL，排序口径随之改变；因此在入口显式拒绝，而不是让它变成一次静默的引擎切换。
     */
    public static final int MAX_RESULT_WINDOW = 10_000;

    private SearchPagingPolicy() {
    }

    /**
     * 该页是否超出可检索窗口。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return true 表示 from + size 超过上限
     */
    public static boolean exceedsWindow(int page, int size) {
        if (page < 1 || size < 1) {
            return false;
        }
        long from = (long) (page - 1) * size;
        return from + size > MAX_RESULT_WINDOW;
    }
}
