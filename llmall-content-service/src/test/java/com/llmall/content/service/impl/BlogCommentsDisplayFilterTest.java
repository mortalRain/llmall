package com.llmall.content.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmall.common.utils.UserContext;
import com.llmall.content.domain.po.Blog;
import com.llmall.content.domain.po.BlogComments;
import com.llmall.content.mapper.BlogCommentsMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论分页的读取口径：只能出 status=0（正常）的评论，被举报(1) / 禁止查看(2) 一律不展示。
 *
 * 【为什么要这条用例】这两个状态目前没有任何写入路径（举报与审核链路未实现，见台账 C-04），
 * 于是"过滤是否生效"在功能上无从观测：就算把过滤条件删掉，正常数据下测试也照样是绿的。
 * 唯一能锁住的判据是查询条件本身 —— 这里把 mapper 换成桩，截下 `pageComments` 实际传下去的 Wrapper，
 * 断言它带上了 status 条件且取值是 0。这样"有人把过滤删了 / 把它降级成 ne(status, 2)"都会立刻红。
 *
 * 【怎么做到不依赖 DB】只用动态代理桩住 BlogCommentsMapper.selectPage(IPage, Wrapper)，不连库；
 * `requireVisibleBlog` 需要一篇可见笔记，同样用桩返回 status=1 且作者为当前用户的 Blog。
 * Lambda 条件要把字段解析成列名，依赖 MyBatis-Plus 的 TableInfo 缓存 —— 用 {@link TableInfoHelper}
 * 现场初始化（不启 Spring、不连库），否则会报 "can not find lambda cache for this entity"。
 */
class BlogCommentsDisplayFilterTest {

    @BeforeAll
    static void initLambdaCache() {
        // Lambda 条件解析列名时依赖 TableInfo 缓存；单测没有 MyBatis 上下文，这里手动建一份
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), BlogComments.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Blog.class);
    }

    /** 截下 selectPage 收到的 Wrapper；返回空页（用例只关心"查询条件"，不关心数据） */
    private Wrapper<BlogComments> captureWrapper() {
        AtomicReference<Wrapper<BlogComments>> captured = new AtomicReference<>();
        BlogCommentsMapper mapper = (BlogCommentsMapper) Proxy.newProxyInstance(
                BlogCommentsMapper.class.getClassLoader(),
                new Class<?>[]{BlogCommentsMapper.class},
                (proxy, method, args) -> {
                    if ("selectPage".equals(method.getName())) {
                        // 参数顺序：IPage, Wrapper
                        @SuppressWarnings("unchecked")
                        Wrapper<BlogComments> wrapper = (Wrapper<BlogComments>) args[1];
                        captured.set(wrapper);
                        Page<BlogComments> empty = new Page<>();
                        empty.setRecords(new ArrayList<>());
                        empty.setTotal(0);
                        return empty;
                    }
                    return null;
                });

        // 构造参数顺序与 BlogServiceImpl 的 final 字段声明一致：
        // blogCommentsMapper、blogLikeMapper、shopClient、userClient、redisTemplate、eventPublisher、followService
        BlogServiceImpl service = new BlogServiceImpl(mapper, null, null, null, null, null, null) {
            @Override
            public Blog getById(java.io.Serializable id) {
                // 让 requireVisibleBlog 通过：已发布(status=1) 且作者是当前用户
                Long author = UserContext.getUser();
                return new Blog().setId(1L).setUserId(author).setStatus(1);
            }
        };
        service.pageComments(1L, 1, 10);
        return captured.get();
    }

    @Test
    void pageCommentsMustFilterOutNonNormalStatus() {
        // 造一个"已登录作者"的上下文：requireVisibleBlog 对未登录身份是 fail-closed 的
        UserContext.setUser(1L);
        try {
            Wrapper<BlogComments> wrapper = captureWrapper();
            assertThat(wrapper)
                    .as("评论分页必须把查询条件传给 mapper（只查 status=0）")
                    .isNotNull();

            String sql = wrapper.getSqlSegment();
            assertThat(sql)
                    .as("查询条件里必须出现 status 过滤：%s", sql)
                    .contains("status");
            assertThat(sql)
                    .as("过滤写法必须是等值（只允许 0），不能降级成 <> 2 —— 后者会把将来新增的状态一起放出来")
                    .doesNotContain("<>");
            assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values())
                    .as("status 的取值必须是 0（正常）；1 被举报 / 2 禁止查看 都不得展示")
                    .contains(0);
            assertThat(sql)
                    .as("仍然按笔记 id 过滤，避免越权读到别的笔记的评论")
                    .contains("blog_id");
        }
        finally {
            UserContext.removeUser();
        }
    }

    @Test
    void wrapperShapeIsLambdaOnCommentsTable() {
        // 附加约束：条件是针对 blog_comments 的 Lambda 条件（防止有人改成手写 SQL 字符串绕过检查）
        UserContext.setUser(1L);
        try {
            Wrapper<BlogComments> wrapper = captureWrapper();
            assertThat(wrapper).isInstanceOf(LambdaQueryWrapper.class);
        }
        finally {
            UserContext.removeUser();
        }
    }
}


