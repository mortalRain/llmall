package com.llmall.search.task;

import com.llmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 热词落库任务：Redis ZSet 里的热词计数定期汇总到 MySQL，供报表与离线分析使用。
 * 这样设计的原因是热词是**高频写**（每次搜索 +1），直接写库会把 MySQL 打满。
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
