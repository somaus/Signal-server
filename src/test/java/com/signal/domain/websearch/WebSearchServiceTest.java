package com.signal.domain.websearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.monitoring.entity.MatchType;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import com.signal.domain.websearch.entity.UnavailableReason;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.entity.WebSearchStatus;
import com.signal.domain.websearch.repository.WebSearchRepository;
import com.signal.domain.websearch.service.WebSearchRunner;
import com.signal.domain.websearch.service.WebSearchService;
import com.signal.global.file.FileStorage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class WebSearchServiceTest {

    @Mock
    private WebSearchRepository repository;
    @Mock
    private WebSearchRunner runner;
    @Mock
    private ReverseImageSearcher searcher;
    @Mock
    private FileStorage fileStorage;

    @SuppressWarnings("unchecked")
    private WebSearchService service(ReverseImageSearcher searcherOrNull) {
        ObjectProvider<ReverseImageSearcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(searcherOrNull);
        return new WebSearchService(repository, runner, provider, 3, 10, 2);
    }

    @Test
    void 기능이_꺼져_있으면_아무것도_하지_않는다() {
        service(null).start(1L, "/uploads/a.png", false, "u:1");

        verify(repository, never()).save(any());
        verify(runner, never()).search(any(), any(), any());
    }

    @Test
    void 영상은_UNAVAILABLE로_기록하고_검색하지_않는다() {
        service(searcher).start(1L, "/uploads/a.mp4", true, "u:1");

        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(WebSearchStatus.UNAVAILABLE);
        assertThat(saved.getValue().getUnavailableReason()).isEqualTo(UnavailableReason.VIDEO);
        verify(runner, never()).search(any(), any(), any());
    }

    @Test
    void 오늘_상한에_도달하면_UNAVAILABLE로_기록하고_검색하지_않는다() {
        when(repository.countByCreatedAtGreaterThanEqualAndStatusNot(any(), eq(WebSearchStatus.UNAVAILABLE))).thenReturn(3L);

        service(searcher).start(1L, "/uploads/a.png", false, "u:1");

        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(WebSearchStatus.UNAVAILABLE);
        verify(runner, never()).search(any(), any(), any());
    }

    @Test
    void 사용자별_하루_횟수를_다_쓰면_OWNER_LIMIT이다() {
        when(repository.countByOwnerKeyAndCreatedAtGreaterThanEqualAndStatusNot(eq("u:1"), any(), any())).thenReturn(2L);

        service(searcher).start(1L, "/uploads/a.png", false, "u:1");

        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUnavailableReason()).isEqualTo(UnavailableReason.OWNER_LIMIT);
        verify(runner, never()).search(any(), any(), any());
    }

    @Test
    void 이번_달_상한에_도달하면_MONTHLY_LIMIT이다() {
        when(repository.countByCreatedAtGreaterThanEqualAndStatusNot(any(), any())).thenReturn(0L, 10L);

        service(searcher).start(1L, "/uploads/a.png", false, "u:1");

        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUnavailableReason()).isEqualTo(UnavailableReason.MONTHLY_LIMIT);
    }

    @Test
    void 상한_안이면_PROCESSING으로_기록하고_비동기_검색을_시작한다() {
        when(repository.countByCreatedAtGreaterThanEqualAndStatusNot(any(), any())).thenReturn(2L);

        service(searcher).start(1L, "/uploads/a.png", false, "u:1");

        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(WebSearchStatus.PROCESSING);
        verify(runner).search(1L, "/uploads/a.png", searcher);
    }

    @Test
    void 저장이_실패해도_예외를_던지지_않는다() {
        when(repository.countByCreatedAtGreaterThanEqualAndStatusNot(any(), any())).thenReturn(0L);
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        service(searcher).start(1L, "/uploads/a.png", false, "u:1");   // 탐지 요청이 깨지면 안 된다
    }

    @Test
    void 러너는_결과를_기록하고_실패하면_FAILED로_둔다() {
        WebSearchRunner real = new WebSearchRunner(repository, fileStorage);
        WebSearch ok = WebSearch.processing(1L, "u:1");
        WebSearch bad = WebSearch.processing(2L, "u:1");
        when(repository.findByDetectionId(1L)).thenReturn(Optional.of(ok));
        when(repository.findByDetectionId(2L)).thenReturn(Optional.of(bad));
        when(fileStorage.load(any())).thenReturn(new byte[]{1});
        when(searcher.search(any()))
                .thenReturn(List.of(new SearchHit("https://a.com", null, "A", MatchType.FULL)))
                .thenThrow(new IllegalStateException("boom"));

        real.search(1L, "/uploads/a.png", searcher);
        real.search(2L, "/uploads/b.png", searcher);

        assertThat(ok.getStatus()).isEqualTo(WebSearchStatus.COMPLETED);
        assertThat(ok.getMatches()).hasSize(1);
        assertThat(bad.getStatus()).isEqualTo(WebSearchStatus.FAILED);
    }

    @Test
    void 같은_사진은_두_번_검색하지_않는다() {
        when(repository.findByDetectionId(1L)).thenReturn(Optional.of(WebSearch.processing(1L, "u:1")));

        service(searcher).start(1L, "/uploads/a.png", false, "u:1");

        verify(repository, never()).save(any());
        verify(runner, never()).search(any(), any(), any());
    }

    @Test
    void 오래된_PROCESSING은_조회할_때_FAILED로_정리한다() throws Exception {
        WebSearch stale = WebSearch.processing(1L, "u:1");
        java.lang.reflect.Field createdAt = WebSearch.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.set(stale, java.time.LocalDateTime.now().minusMinutes(10));
        when(repository.findByDetectionId(1L)).thenReturn(Optional.of(stale));

        WebSearch found = service(searcher).find(1L);

        assertThat(found.getStatus()).isEqualTo(WebSearchStatus.FAILED);
        verify(repository).save(stale);
    }

    @Test
    void 방금_시작한_PROCESSING은_그대로_둔다() {
        WebSearch fresh = WebSearch.processing(1L, "u:1");
        when(repository.findByDetectionId(1L)).thenReturn(Optional.of(fresh));

        assertThat(service(searcher).find(1L).getStatus()).isEqualTo(WebSearchStatus.PROCESSING);
        verify(repository, never()).save(any());
    }

    @Test
    void 계정을_지우면_그_사용자의_검색_기록을_지운다() {
        service(searcher).deleteByOwner(7L);

        verify(repository).deleteByOwnerKey("u:7");
    }

    @Test
    void 분석의_검색은_기능이_꺼져_있으면_DISABLED로_돌려주고_아무것도_저장하지_않는다() {
        var outcome = service(null).searchForAssessment("u:1", new byte[] {1});

        assertThat(outcome.status()).isEqualTo(WebSearchStatus.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(UnavailableReason.DISABLED);
        verify(repository, never()).save(any());
    }

    @Test
    void 분석의_검색은_한도에_닿으면_탐지_id_없는_UNAVAILABLE_행을_남기고_검색하지_않는다() {
        when(repository.countByCreatedAtGreaterThanEqualAndStatusNot(any(), eq(WebSearchStatus.UNAVAILABLE))).thenReturn(3L);

        var outcome = service(searcher).searchForAssessment("u:1", new byte[] {1});

        assertThat(outcome.status()).isEqualTo(WebSearchStatus.UNAVAILABLE);
        assertThat(outcome.reason()).isEqualTo(UnavailableReason.DAILY_LIMIT);
        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDetectionId()).isNull();
        verify(searcher, never()).search(any());
    }

    @Test
    void 분석의_검색은_찾은_페이지를_같은_이미지와_비슷한_이미지로_세고_주소는_저장하지_않는다() {
        when(repository.save(any(WebSearch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(searcher.search(any())).thenReturn(List.of(
                new SearchHit("https://a.example/1", null, "가", MatchType.FULL),
                new SearchHit("https://a.example/2", null, "나", MatchType.PARTIAL),
                new SearchHit("https://a.example/3", null, "다", MatchType.SIMILAR),
                new SearchHit("https://a.example/4", null, "라", MatchType.PAGE)));

        var outcome = service(searcher).searchForAssessment("u:1", new byte[] {1});

        assertThat(outcome.isCompleted()).isTrue();
        assertThat(outcome.sameImageCount()).isEqualTo(2);
        assertThat(outcome.similarImageCount()).isEqualTo(2);
        ArgumentCaptor<WebSearch> saved = ArgumentCaptor.forClass(WebSearch.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        WebSearch last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(WebSearchStatus.COMPLETED);
        assertThat(last.getMatches()).isEmpty();
    }

    @Test
    void 분석의_검색이_예외로_실패하면_FAILED로_기록하고_FAILED를_돌려준다() {
        when(repository.save(any(WebSearch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(searcher.search(any())).thenThrow(new IllegalStateException("검색 서버 오류"));

        var outcome = service(searcher).searchForAssessment("u:1", new byte[] {1});

        assertThat(outcome.status()).isEqualTo(WebSearchStatus.FAILED);
    }
}
