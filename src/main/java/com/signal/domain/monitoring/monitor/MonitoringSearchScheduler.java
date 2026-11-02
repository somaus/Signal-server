package com.signal.domain.monitoring.monitor;

import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매시 정각에 검색 시각이 지난 모니터링을 찾아 검색한다(등록 5분 이내는 제외). 하루 상한은 {@link SearchFaceMonitor}가 지킨다. */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "monitoring.mode", havingValue = "search")
public class MonitoringSearchScheduler {

    /** 방금 등록한 모니터링은 등록 직후의 비동기 검색이 맡는다. 같은 모니터링을 두 번 검색하지 않도록 잠시 뺀다. */
    private static final Duration INITIAL_SEARCH_GRACE = Duration.ofMinutes(5);

    private final MonitoringRepository monitoringRepository;
    private final SearchFaceMonitor searchFaceMonitor;
    private final int batchSize;

    public MonitoringSearchScheduler(MonitoringRepository monitoringRepository,
                                     SearchFaceMonitor searchFaceMonitor,
                                     @Value("${monitoring.search.daily-limit:30}") int batchSize) {
        this.monitoringRepository = monitoringRepository;
        this.searchFaceMonitor = searchFaceMonitor;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${monitoring.search.cron:0 0 * * * *}")
    public void searchDueMonitorings() {
        LocalDateTime now = LocalDateTime.now();
        List<Monitoring> due = monitoringRepository.findByStatusAndNextSearchAtLessThanEqualAndCreatedAtBeforeOrderByNextSearchAtAsc(
                MonitoringStatus.ACTIVE, now, now.minus(INITIAL_SEARCH_GRACE), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return;
        }
        log.info("검색 대상 모니터링 {}건", due.size());
        due.forEach(monitoring -> searchFaceMonitor.search(monitoring.getId()));
    }
}
