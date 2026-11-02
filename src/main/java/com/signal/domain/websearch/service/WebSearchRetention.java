package com.signal.domain.websearch.service;

import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 검색 결과(페이지 URL 목록)와 소유자 키는 개인정보에 가까우므로 보존 기간이 지나면 지운다. */
@Slf4j
@Component
public class WebSearchRetention {

    private final WebSearchService webSearchService;
    private final int retentionDays;

    public WebSearchRetention(WebSearchService webSearchService,
                              @Value("${web-search.retention-days:30}") int retentionDays) {
        this.webSearchService = webSearchService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void purge() {
        int deleted = webSearchService.purgeOlderThan(LocalDateTime.now().minusDays(retentionDays));
        if (deleted > 0) {
            log.info("보존 기간({}일)이 지난 웹 검색 기록을 삭제했습니다: {}건", retentionDays, deleted);
        }
    }
}
