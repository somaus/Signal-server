package com.signal.domain.monitoring.monitor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MonitoringSearchSchedulerTest {

    @Mock
    private MonitoringRepository monitoringRepository;
    @Mock
    private SearchFaceMonitor searchFaceMonitor;

    @Test
    void 검색_시각이_지난_모니터링을_모두_검색한다() {
        Monitoring a = Monitoring.builder().userId(1L).referenceImageUrl("a").build();
        Monitoring b = Monitoring.builder().userId(2L).referenceImageUrl("b").build();
        ReflectionTestUtils.setField(a, "id", 10L);
        ReflectionTestUtils.setField(b, "id", 11L);
        when(monitoringRepository.findByStatusAndNextSearchAtLessThanEqualAndCreatedAtBeforeOrderByNextSearchAtAsc(
                eq(MonitoringStatus.ACTIVE), any(), any(), any(Pageable.class))).thenReturn(List.of(a, b));

        new MonitoringSearchScheduler(monitoringRepository, searchFaceMonitor, 30).searchDueMonitorings();

        verify(searchFaceMonitor).search(10L);
        verify(searchFaceMonitor).search(11L);
    }

    @Test
    void 대상이_없으면_검색하지_않는다() {
        when(monitoringRepository.findByStatusAndNextSearchAtLessThanEqualAndCreatedAtBeforeOrderByNextSearchAtAsc(
                eq(MonitoringStatus.ACTIVE), any(), any(), any(Pageable.class))).thenReturn(List.of());

        new MonitoringSearchScheduler(monitoringRepository, searchFaceMonitor, 30).searchDueMonitorings();

        verify(searchFaceMonitor, never()).search(any());
    }
}
