package com.llmall.content.domain.vo;

import com.llmall.api.dto.UserProfileDTO;
import com.llmall.content.domain.po.Blog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BlogVO 作者资料组装口径。
 *
 * 【为什么要锁昵称回退】演示库的 user.nick_name 为空，评价列表若直接把 null 渲染成空白，
 * 作者名就丢了；而"有昵称时不要覆盖用户真实昵称"同样重要（否则会把 Jack 显示成同城用户1）。
 * 这两条都只有在 VO 组装层断言才不依赖数据库/用户服务。
 */
class BlogVOTest {

    private Blog blog() {
        return new Blog()
                .setId(100L)
                .setShopId(10L)
                .setUserId(1L)
                .setTitle("包厢音效不错")
                .setContent("公司团建订了大包厢，麦克风没有啸叫，歌单更新也及时。")
                .setScore(5)
                .setLiked(3)
                .setComments(1)
                .setStatus(1);
    }

    @Test
    void missingNicknameFallsBackToCityUserWithId() {
        BlogVO vo = BlogVO.from(blog(), null);

        assertThat(vo).isNotNull();
        assertThat(vo.getNickname()).isEqualTo("同城用户1");
        assertThat(vo.getIcon()).isNull();
        // Blog 原字段必须完整保留，不能因为组装作者资料丢内容
        assertThat(vo.getContent()).contains("麦克风");
        assertThat(vo.getScore()).isEqualTo(5);
        assertThat(vo.getUserId()).isEqualTo(1L);
    }

    @Test
    void blankNicknameAlsoFallsBack() {
        UserProfileDTO profile = new UserProfileDTO().setId(1L).setNickname("   ").setIcon("https://example.com/a.png");

        BlogVO vo = BlogVO.from(blog(), profile);

        assertThat(vo.getNickname()).isEqualTo("同城用户1");
        // 头像与昵称相互独立：昵称缺失不应把已上传的头像一起丢掉
        assertThat(vo.getIcon()).isEqualTo("https://example.com/a.png");
    }

    @Test
    void realNicknameAndIconAreKept() {
        UserProfileDTO profile = new UserProfileDTO().setId(1L).setNickname("Jack").setIcon("https://example.com/jack.png");

        BlogVO vo = BlogVO.from(blog(), profile);

        assertThat(vo.getNickname()).isEqualTo("Jack");
        assertThat(vo.getIcon()).isEqualTo("https://example.com/jack.png");
    }
}
