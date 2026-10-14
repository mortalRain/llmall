package com.llmall.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.experimental.Accessors;

import javax.validation.constraints.Min;
import java.util.regex.Pattern;

@Data
@Schema(description = "分页查询条件")
@Accessors(chain = true)
public class PageQuery {
    public static final Integer DEFAULT_PAGE_SIZE = 20;
    public static final Integer DEFAULT_PAGE_NUM = 1;
    /** 单页硬上限：任何分页入口都不得超过（可由 llmall.page.max-size 覆盖，见 PageConfig） */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 全局每页条数上限。放在公共件里兜底：分页是所有业务接口共用的能力，漏掉任何一个入口（新加的分页接口、
     * Feign 之间互调透传的参数）都等于把整张表开放出去。
     * volatile：启动时写一次，之后被所有请求线程读。
     */
    private static volatile int maxPageSize = MAX_PAGE_SIZE;

    public static void setMaxPageSize(Integer max) {
        if (max != null && max > 0) {
            maxPageSize = max;
        }
    }

    public static int getMaxPageSize() {
        return maxPageSize;
    }

    /**
     * 页码收敛：负数/0/null 一律当第 1 页。
     *
     * 【注意】MyBatis-Plus 的 Page 对小于 1 的 current 会算出负数 offset，某些方言下还会被当成"未分页"。
     */
    public static int normalizePageNo(Integer pageNo) {
        return (pageNo == null || pageNo < 1) ? DEFAULT_PAGE_NUM : pageNo;
    }

    /**
     * 每页条数收敛到 [1, maxPageSize]。
     *
     * 【注意】MyBatis-Plus 3.4.2 的 {@code new Page<>(current, size)} 在 size 小于 0 时表示不分页
     * （等价于 selectList），即 {@code /items/page?pageSize=-1} 能一次拉走整张商品表。
     * 非法值取默认、超大取上限而不抛 400：分页参数属可自愈的输入，把它变成错误会让前端翻页组件整页报错。
     */
    public static int normalizeSize(Integer size) {
        return normalizeSize(size, DEFAULT_PAGE_SIZE, maxPageSize);
    }

    public static int normalizeSize(Integer size, int defaultSize) {
        return normalizeSize(size, defaultSize, maxPageSize);
    }

    /**
     * @param defaultSize size 缺失/非法时的默认值（各接口默认值不同，保留原语义）
     * @param maxSize     该接口自己的上限；只会把全局上限收得更紧，不会突破它
     */
    public static int normalizeSize(Integer size, int defaultSize, int maxSize) {
        int dft = defaultSize < 1 ? DEFAULT_PAGE_SIZE : defaultSize;
        int cap = maxSize < 1 ? maxPageSize : Math.min(maxSize, maxPageSize);
        if (size == null || size < 1) {
            return Math.min(dft, cap);
        }
        return Math.min(size, cap);
    }

    /**
     * 排序列白名单：单个数据库标识符（字母/下划线开头，其后字母/数字/下划线，最长 64）。
     *
     * 【注意】sortBy 是前端传入的字符串、会被直接拼进 ORDER BY，而 MyBatis-Plus 3.4.2 不对列名做净化，
     * 原样拼接即可构造布尔盲注（如 CASE WHEN(子查询) THEN ...），所以必须限定为单个数据库标识符。
     * 非法值一律当作"没传"，退回接口默认排序——排序字段属于可自愈的脏输入，不该让整页报错。
     */
    private static final Pattern SAFE_SORT_BY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** 返回可安全拼进 ORDER BY 的排序列；为空或形态非法时返回 null（调用方应退回默认排序）。 */
    public String safeSortBy() {
        if (sortBy == null) {
            return null;
        }
        String trimmed = sortBy.trim();
        return SAFE_SORT_BY.matcher(trimmed).matches() ? trimmed : null;
    }

    @Schema(description = "页码")
    @Min(value = 1, message = "页码不能小于1")
    private Integer pageNo = DEFAULT_PAGE_NUM;
    @Schema(description = "每页数量")
    @Min(value = 1, message = "每页查询数量不能小于1")
    private Integer pageSize = DEFAULT_PAGE_SIZE;
    @Schema(description = "是否升序")
    private Boolean isAsc = true;
    @Schema(description = "排序方式")
    private String sortBy;

    public int from(){
        // 与 toMpPage 用同一套收敛规则：手写 SQL（LIMIT offset,size）的调用方也不能绕过上限
        return (normalizePageNo(pageNo) - 1) * normalizeSize(pageSize);
    }

    public <T> Page<T> toMpPage(OrderItem... orderItems) {
        normalizeSelf();
        Page<T> page = new Page<>(pageNo, pageSize);
        if (orderItems != null && orderItems.length > 0) {
            for (OrderItem orderItem : orderItems) {
                page.addOrder(orderItem);
            }
            return page;
        }
        String safeSort = safeSortBy();
        if (safeSort != null){
            OrderItem orderItem = new OrderItem();
            orderItem.setAsc(isAsc);
            orderItem.setColumn(safeSort);
            page.addOrder(orderItem);
        }
        return page;
    }

    public <T> Page<T> toMpPage(String defaultSortBy, boolean isAsc) {
        normalizeSelf();
        String safeSort = safeSortBy();
        if (safeSort == null){
            sortBy = defaultSortBy;
            this.isAsc = isAsc;
        } else {
            sortBy = safeSort;
        }
        Page<T> page = new Page<>(pageNo, pageSize);
        OrderItem orderItem = new OrderItem();
        orderItem.setAsc(this.isAsc);
        orderItem.setColumn(sortBy);
        page.addOrder(orderItem);
        return page;
    }

    /**
     * 把收敛结果写回字段：这样 from() / 业务代码里读 pageNo、pageSize 时拿到的是同一份值，
     * 否则会出现"分页查询按 100 条取、业务按 -1 条算"的隐蔽不一致。
     */
    private void normalizeSelf() {
        this.pageNo = normalizePageNo(pageNo);
        this.pageSize = normalizeSize(pageSize);
        // isAsc 是包装类型：`?isAsc=`（空串）会被 Spring 绑成 null，而 OrderItem.setAsc 收的是原始
        // boolean，直接传会拆箱 NPE → 500，这里统一收敛为默认升序。
        if (this.isAsc == null) {
            this.isAsc = true;
        }
    }
    public <T> Page<T> toMpPageDefaultSortByCreateTimeDesc() {
        return toMpPage("create_time", false);
    }
}
