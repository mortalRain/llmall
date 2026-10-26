package com.llmall.content.domain.vo;

import cn.hutool.core.util.StrUtil;
import com.llmall.api.dto.UserProfileDTO;
import com.llmall.common.utils.BeanUtils;
import com.llmall.content.domain.po.Blog;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 门店评价对外视图：Blog 全字段 + 作者昵称/头像。
 *
 * 【为什么需要它】blog 表只有 user_id，昵称/头像在用户库，内容域不能直连用户库（数据所有权原则）。
 * 若前端按 userId 逐条查资料，一页 N 条就是 N+1；本视图由内容域一次批量回填。
 *
 * 【昵称为空的口径】用户未填写昵称（演示库 user.nick_name 为空）时回退成「同城用户{id}」，
 * 保证评价区始终有可读的作者名；头像为空时由前端用昵称首字 + 稳定背景色兜底。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class BlogVO extends Blog {

    private static final long serialVersionUID = 1L;

    /** 作者昵称；查不到用户资料或昵称为空时回退「同城用户{id}」 */
    private String nickname;

    /** 作者头像 URL；可能为空 */
    private String icon;

    /**
     * 由 Blog + 用户资料组装视图。
     *
     * 【注意】用户资料缺失（用户服务不可用 / 用户已删除）时不能抛异常：评价内容本身仍然可读，
     * 只把作者降级成「同城用户{id}」，避免整个评价列表因为展示增强字段而 500。
     */
    public static BlogVO from(Blog blog, UserProfileDTO profile) {
        if (blog == null) {
            return null;
        }
        BlogVO vo = BeanUtils.copyBean(blog, BlogVO.class);
        String nickname = profile == null ? null : profile.getNickname();
        if (StrUtil.isBlank(nickname)) {
            nickname = blog.getUserId() == null ? "同城用户" : "同城用户" + blog.getUserId();
        }
        vo.setNickname(nickname);
        vo.setIcon(profile == null ? null : profile.getIcon());
        return vo;
    }
}
