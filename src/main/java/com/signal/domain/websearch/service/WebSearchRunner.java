package com.signal.domain.websearch.service;

import com.signal.domain.monitoring.monitor.ReverseImageSearcher;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import com.signal.domain.websearch.entity.WebMatch;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.repository.WebSearchRepository;
import com.signal.global.file.FileStorage;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 비동기로 역이미지 검색을 수행하고 결과를 기록한다. 어떤 실패도 합성 분석에는 영향을 주지 않는다. */
@Slf4j
@Component
public class WebSearchRunner {

    private final WebSearchRepository webSearchRepository;
    private final FileStorage fileStorage;

    public WebSearchRunner(WebSearchRepository webSearchRepository, FileStorage fileStorage) {
        this.webSearchRepository = webSearchRepository;
        this.fileStorage = fileStorage;
    }

    @Async
    public void search(Long detectionId, String fileUrl, ReverseImageSearcher searcher) {
        WebSearch webSearch = webSearchRepository.findByDetectionId(detectionId).orElse(null);
        if (webSearch == null) {
            return;
        }
        try {
            List<SearchHit> hits = searcher.search(fileStorage.load(fileUrl));
            webSearch.complete(hits.stream()
                    .map(hit -> new WebMatch(hit.pageUrl(), truncate(hit.title(), 500), hit.matchType()))
                    .toList());
            log.info("웹 검색 완료: detectionId={}, 결과={}건", detectionId, hits.size());
        } catch (Exception e) {
            webSearch.fail();
            // 스택 대신 메시지만 남긴다(검색기가 키를 뺀 메시지로 바꿔 던진다).
            log.error("웹 검색 실패: detectionId={}, 원인={}", detectionId, e.toString());
        }
        webSearchRepository.save(webSearch);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
