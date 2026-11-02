package com.signal.domain.monitoring.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.monitoring.entity.Detection;
import com.signal.domain.monitoring.entity.MatchType;
import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import com.signal.domain.monitoring.repository.DetectionRepository;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import com.signal.global.file.FileStorage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchFaceMonitorTest {

    @Mock
    private MonitoringRepository monitoringRepository;
    @Mock
    private DetectionRepository detectionRepository;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private ReverseImageSearcher searcher;

    private SearchFaceMonitor monitor;
    private Monitoring monitoring;

    @BeforeEach
    void setUp() {
        monitor = new SearchFaceMonitor(monitoringRepository, detectionRepository, fileStorage, searcher, 24, 3, 30);
        monitoring = Monitoring.builder().userId(1L).referenceImageUrl("/uploads/monitorings/a.png").build();
    }

    private void givenMonitoringExists() {
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));
        when(monitoringRepository.countByLastSearchedAtGreaterThanEqual(any())).thenReturn(0L);
    }

    @Test
    void 새로_발견된_페이지만_저장하고_다음_검색을_하루_뒤로_잡는다() {
        givenMonitoringExists();
        when(fileStorage.load("/uploads/monitorings/a.png")).thenReturn(new byte[]{1});
        when(searcher.search(any())).thenReturn(List.of(
                new SearchHit("https://a.com/1", "https://a.com/1.jpg", "A", MatchType.FULL),
                new SearchHit("https://old.com", null, "old", MatchType.PAGE)));
        when(detectionRepository.existsByMonitoringIdAndSourceUrl(1L, "https://a.com/1")).thenReturn(false);
        when(detectionRepository.existsByMonitoringIdAndSourceUrl(1L, "https://old.com")).thenReturn(true);
        when(monitoringRepository.existsById(1L)).thenReturn(true);

        monitor.search(1L);

        ArgumentCaptor<Detection> saved = ArgumentCaptor.forClass(Detection.class);
        verify(detectionRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceUrl()).isEqualTo("https://a.com/1");
        assertThat(saved.getValue().getTitle()).isEqualTo("A");
        assertThat(saved.getValue().getMatchType()).isEqualTo(MatchType.FULL);
        assertThat(monitoring.getLastSearchedAt()).isNotNull();
        assertThat(monitoring.getNextSearchAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(monitoring.getConsecutiveFailures()).isZero();
        verify(monitoringRepository).save(monitoring);
    }

    @Test
    void 검색이_실패하면_실패_횟수만_올리고_예외를_던지지_않는다() {
        givenMonitoringExists();
        when(fileStorage.load(any())).thenReturn(new byte[]{1});
        when(searcher.search(any())).thenThrow(new IllegalStateException("boom"));
        when(monitoringRepository.existsById(1L)).thenReturn(true);

        monitor.search(1L);

        assertThat(monitoring.getConsecutiveFailures()).isEqualTo(1);
        assertThat(monitoring.getStatus()).isEqualTo(MonitoringStatus.ACTIVE);
        verify(detectionRepository, never()).save(any());
    }

    @Test
    void 다시_시도해도_소용없는_실패는_바로_PAUSED가_된다() {
        givenMonitoringExists();
        when(fileStorage.load(any())).thenReturn(new byte[]{1});
        when(searcher.search(any())).thenThrow(new NonRetryableSearchException("bad request"));
        when(monitoringRepository.existsById(1L)).thenReturn(true);

        monitor.search(1L);

        assertThat(monitoring.getStatus()).isEqualTo(MonitoringStatus.PAUSED);
    }

    @Test
    void 연속_실패가_한도에_이르면_PAUSED가_된다() {
        givenMonitoringExists();
        when(fileStorage.load(any())).thenReturn(new byte[]{1});
        when(searcher.search(any())).thenThrow(new IllegalStateException("boom"));
        when(monitoringRepository.existsById(1L)).thenReturn(true);

        monitor.search(1L);
        monitor.search(1L);
        monitor.search(1L);

        assertThat(monitoring.getStatus()).isEqualTo(MonitoringStatus.PAUSED);
    }

    @Test
    void PAUSED면_검색하지_않는다() {
        monitoring.recordSearchFailure(LocalDateTime.now(), java.time.Duration.ofHours(24), 1);
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));

        monitor.search(1L);

        verify(searcher, never()).search(any());
    }

    @Test
    void 하루_상한에_도달하면_검색하지_않는다() {
        when(monitoringRepository.findById(1L)).thenReturn(Optional.of(monitoring));
        when(monitoringRepository.countByLastSearchedAtGreaterThanEqual(any())).thenReturn(30L);

        monitor.search(1L);

        verify(searcher, never()).search(any());
        verify(monitoringRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_모니터링이면_아무것도_하지_않는다() {
        when(monitoringRepository.findById(1L)).thenReturn(Optional.empty());

        monitor.search(1L);

        verify(searcher, never()).search(any());
    }

    @Test
    void 검색_중_삭제된_모니터링은_다시_저장하지_않는다() {
        givenMonitoringExists();
        when(fileStorage.load(any())).thenReturn(new byte[]{1});
        when(searcher.search(any())).thenReturn(List.of());
        when(monitoringRepository.existsById(1L)).thenReturn(false);

        monitor.search(1L);

        verify(monitoringRepository, never()).save(any());
    }
}
