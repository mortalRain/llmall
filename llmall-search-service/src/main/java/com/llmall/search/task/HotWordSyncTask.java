package com.llmall.search.task;

import com.llmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 热词两段式落库：Redis ZSet 实时计数 + 定时任务汇总写 MySQL；落库后的 search_hot_word 由
 * 搜索建议（ISearchService.suggest 的前缀匹配查询）读取，检索本身不读这张表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotWordSyncTask {

    private final ISearchService searchService;

    @Scheduled(
            initialDelayString = "${llmall.search.hotword.initial-delay-ms:120000}",
            fixedDelayString = "${llmall.search.hotword.interval-ms:600000}")
    public void sync() {
        try {
            searchService.syncHotWordsToDb();
        } catch (Exception e) {
            log.error("热词落库任务异常", e);
        }
    }
}
