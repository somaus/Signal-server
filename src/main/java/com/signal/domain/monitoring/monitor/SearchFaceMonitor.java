package com.signal.domain.monitoring.monitor;

import com.signal.domain.monitoring.entity.Detection;
import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.entity.MonitoringStatus;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import com.signal.domain.monitoring.repository.DetectionRepository;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import com.signal.global.file.FileStorage;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 기준 이미지로 웹을 역검색해 새로 발견된 페이지를 탐지 결과로 저장한다. 등록 직후 한 번, 이후에는
 * {@link MonitoringSearchScheduler}가 하루에 한 번 호출한다. 외부 API의 무료 쿼터를 지키기 위해 하루 전체
 * 검색 횟수에 상한을 두며, 상한에 이르면 건너뛰고 다음 날 이어서 처리한다.
 *
 * StubFaceMonitor와 같은 이유로 MonitoringService가 아닌 Repository에 직접 접근한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "monitoring.mode", havingValue = "search")
public class SearchFaceMonitor implements FaceMonitor {

    /** 일일 상한은 한국 시간 자정에 초기화한다(컨테이너가 UTC여도 같다). */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MonitoringRepository monitoringRepository;
    private final DetectionRepository detectionRepository;
    private final FileStorage fileStorage;
    private final ReverseImageSearcher searcher;
    private final Duration interval;
    private final int maxFailures;
    private final int dailyLimit;

    public SearchFaceMonitor(MonitoringRepository monitoringRepository,
                             DetectionRepository detectionRepository,
                             FileStorage fileStorage,
                             ReverseImageSearcher searcher,
                             @Value("${monitoring.search.interval-hours:24}") long intervalHours,
                             @Value("${monitoring.search.max-failures:5}") int maxFailures,
                             @Value("${monitoring.search.daily-limit:30}") int dailyLimit) {
        this.monitoringRepository = monitoringRepository;
        this.detectionRepository = detectionRepository;
        this.fileStorage = fileStorage;
        this.searcher = searcher;
        this.interval = Duration.ofHours(intervalHours);
        this.maxFailures = maxFailures;
        this.dailyLimit = dailyLimit;
    }

    @Override
    @Async
    public void startMonitoring(Long monitoringId, String referenceImageUrl) {
        search(monitoringId);
    }

    /** 동기 실행. 실패해도 예외를 던지지 않고 실패 횟수만 기록한다. */
    public void search(Long monitoringId) {
        Monitoring monitoring = monitoringRepository.findById(monitoringId).orElse(null);
        if (monitoring == null || monitoring.getStatus() != MonitoringStatus.ACTIVE) {
            return;
        }
        if (monitoringRepository.countByLastSearchedAtGreaterThanEqual(startOfTodayKst()) >= dailyLimit) {
            log.warn("오늘 검색 상한({}건)에 도달해 건너뜁니다. monitoringId={}", dailyLimit, monitoringId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        try {
            byte[] image = fileStorage.load(monitoring.getReferenceImageUrl());
            List<SearchHit> hits = searcher.search(image);

            int added = 0;
            for (SearchHit hit : hits) {
                if (detectionRepository.existsByMonitoringIdAndSourceUrl(monitoringId, hit.pageUrl())) {
                    continue;
                }
                detectionRepository.save(Detection.builder()
                        .monitoringId(monitoringId)
                        .sourceUrl(hit.pageUrl())
                        .thumbnailUrl(hit.imageUrl())
                        .title(truncate(hit.title(), 500))
                        .matchType(hit.matchType())
                        .build());
                added++;
            }
            monitoring.recordSearchSuccess(now, interval);
            log.info("모니터링 검색 완료: monitoringId={}, 검색결과={}, 신규={}", monitoringId, hits.size(), added);
        } catch (NonRetryableSearchException e) {
            monitoring.pauseAfterFatalFailure(now);
            log.error("모니터링 검색을 다시 시도할 수 없어 멈춥니다: monitoringId={}", monitoringId, e);
        } catch (Exception e) {
            monitoring.recordSearchFailure(now, interval, maxFailures);
            log.error("모니터링 검색 실패: monitoringId={}, 연속 실패={}, 상태={}",
                    monitoringId, monitoring.getConsecutiveFailures(), monitoring.getStatus(), e);
        }

        // 검색 중에 사용자가 삭제했다면 되살리지 않는다.
        if (monitoringRepository.existsById(monitoringId)) {
            monitoringRepository.save(monitoring);
        }
    }

    /** 저장된 시각은 서버 기본 시간대이므로, 한국 시간 자정을 같은 시간대로 옮겨 비교한다. */
    private static LocalDateTime startOfTodayKst() {
        return LocalDate.now(KST).atStartOfDay(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
