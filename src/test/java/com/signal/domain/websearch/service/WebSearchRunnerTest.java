package com.signal.domain.websearch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.signal.domain.monitoring.entity.MatchType;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.entity.WebSearchStatus;
import com.signal.domain.websearch.repository.WebSearchRepository;
import com.signal.global.file.FileStorage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebSearchRunnerTest {

    private final WebSearchRepository repository = mock(WebSearchRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final ReverseImageSearcher searcher = mock(ReverseImageSearcher.class);
    private final WebSearchRunner runner = new WebSearchRunner(repository, fileStorage);

    @Test
    void 검색_결과를_저장하고_긴_제목은_500자로_자른다() {
        WebSearch webSearch = WebSearch.processing(1L, "u:1");
        given(repository.findByDetectionId(1L)).willReturn(Optional.of(webSearch));
        given(fileStorage.load("url")).willReturn(new byte[] {1});
        given(searcher.search(any())).willReturn(List.of(
                new SearchHit("https://a", null, "t".repeat(600), MatchType.FULL),
                new SearchHit("https://b", null, null, MatchType.SIMILAR)));

        runner.search(1L, "url", searcher);

        assertThat(webSearch.getStatus()).isEqualTo(WebSearchStatus.COMPLETED);
        assertThat(webSearch.getMatches()).hasSize(2);
        assertThat(webSearch.getMatches().get(0).getTitle()).hasSize(500);
        assertThat(webSearch.getMatches().get(1).getTitle()).isNull();
        verify(repository).save(webSearch);
    }

    @Test
    void 검색이_실패하면_FAILED로_저장하고_예외를_삼킨다() {
        WebSearch webSearch = WebSearch.processing(1L, "u:1");
        given(repository.findByDetectionId(1L)).willReturn(Optional.of(webSearch));
        given(searcher.search(any())).willThrow(new IllegalStateException("quota"));

        runner.search(1L, "url", searcher);

        assertThat(webSearch.getStatus()).isEqualTo(WebSearchStatus.FAILED);
        verify(repository).save(webSearch);
    }

    @Test
    void 기록이_없으면_아무것도_하지_않는다() {
        given(repository.findByDetectionId(1L)).willReturn(Optional.empty());

        runner.search(1L, "url", searcher);

        verifyNoInteractions(searcher, fileStorage);
        verify(repository, never()).save(any());
    }
}
