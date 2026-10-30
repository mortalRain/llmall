package com.llmall.content.service.impl;

import com.llmall.common.exception.BadRequestException;
import com.llmall.common.metrics.BizMetrics;
import com.llmall.content.domain.po.Blog;
import com.llmall.content.domain.po.FeedInbox;
import com.llmall.content.domain.vo.FeedPageVO;
import com.llmall.content.mapper.BlogMapper;
import com.llmall.content.mapper.FeedInboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedCursorTest {
    final LocalDateTime time = LocalDateTime.of(2026, 1, 1, 12, 0);
    FeedInboxMapper inbox;
    BlogMapper blogs;
    FollowServiceImpl service;
    @BeforeEach void setup() {
        inbox = mock(FeedInboxMapper.class); blogs = mock(BlogMapper.class);
        service = new FollowServiceImpl(inbox, blogs, mock(StringRedisTemplate.class), mock(BizMetrics.class));
    }
    @Test void anonymousDoesNotQueryData() {
        FeedPageVO page = service.queryFeedItemsV2(null, null, null, null, null, 10);
        assertTrue(page.getItems().isEmpty()); assertFalse(page.getHasMore());
        verifyNoInteractions(inbox, blogs);
    }
    @Test void incompleteOrLegacyOnlyCursorCannotRestartPagination() {
        assertThrows(BadRequestException.class, () -> service.queryFeedItemsV2(1L, null, time, null, null, 10));
        assertThrows(BadRequestException.class, () -> service.queryFeedItemsV2(1L, null, null, 2L, null, 10));
        assertThrows(BadRequestException.class, () -> service.queryFeedItemsV2(1L, null, time, 0L, null, 10));
        assertThrows(BadRequestException.class, () -> service.queryFeedItemsV2(1L, 2L, null, null, null, 10));
        assertThrows(BadRequestException.class, () -> service.queryFeedItemsV2(1L, null, null, null, 2L, 10));
        verifyNoInteractions(inbox, blogs);
    }
    @Test void duplicateSourcesConsumeOnePositionAndKeepInboxIdentity() {
        when(inbox.selectFeedPage(1L, null, null, 2)).thenReturn(List.of(row(3), row(2)));
        when(blogs.selectBigvFeedPage(1L, 1000, null, null, 2)).thenReturn(List.of(blog(3, 2, 1), blog(2, 2, 1)));
        when(blogs.selectBatchIds(anyCollection())).thenReturn(List.of(blog(3, 2, 1)));
        FeedPageVO page = page(1);
        assertEquals(1, page.getItems().size()); assertEquals(3L, page.getItems().get(0).getBlogId());
        assertEquals(103L, page.getItems().get(0).getFeedId());
        assertTrue(page.getHasMore()); assertEquals(3L, page.getNextCi());
    }
    @Test void concurrentDeletionCanProduceEmptyPageThatStillAdvances() {
        when(inbox.selectFeedPage(1L, null, null, 2)).thenReturn(List.of(row(3), row(2)));
        when(blogs.selectBatchIds(anyCollection())).thenReturn(List.of());
        FeedPageVO page = page(1);
        assertTrue(page.getItems().isEmpty()); assertTrue(page.getHasMore());
        assertEquals(time, page.getNextCt()); assertEquals(3L, page.getNextCi());
    }
    @Test void visibilityIsCheckedAgainWhenDetailsChange() {
        when(inbox.selectFeedPage(1L, null, null, 3)).thenReturn(List.of(row(3), row(2)));
        when(blogs.selectBatchIds(anyCollection())).thenReturn(List.of(blog(3, 2, 2), blog(2, 1, 0)));
        FeedPageVO page = page(2);
        assertEquals(List.of(2L), page.getItems().stream().map(item -> item.getBlogId()).collect(java.util.stream.Collectors.toList()));
        assertFalse(page.getHasMore()); assertEquals(2L, page.getNextCi());
    }
    @Test void maxPageSizeBoundsCandidatesAndDetailsAndNoDataEndsPagination() {
        FeedPageVO page = page(999);
        assertTrue(page.getItems().isEmpty()); assertFalse(page.getHasMore());
        verify(inbox).selectFeedPage(1L, null, null, 51);
        verify(blogs).selectBigvFeedPage(1L, 1000, null, null, 51);
        verify(blogs, never()).selectBatchIds(anyCollection());
    }
    @Test void fullButExhaustedPageDoesNotClaimAnotherPage() {
        when(inbox.selectFeedPage(1L, null, null, 3)).thenReturn(List.of(row(3), row(2)));
        when(blogs.selectBatchIds(anyCollection())).thenReturn(List.of(blog(3, 2, 1), blog(2, 2, 1)));
        FeedPageVO page = page(2);
        assertEquals(2, page.getItems().size()); assertFalse(page.getHasMore());
    }
    private FeedPageVO page(int size) { return service.queryFeedItemsV2(1L, null, null, null, null, size); }
    private FeedInbox row(long id) {
        return new FeedInbox().setId(100+id).setBlogId(id).setAuthorId(2L).setCreateTime(time);
    }
    private Blog blog(long id, long author, int status) {
        return new Blog().setId(id).setUserId(author).setStatus(status).setCreateTime(time);
    }
}
