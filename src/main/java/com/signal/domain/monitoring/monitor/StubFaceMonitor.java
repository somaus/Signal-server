package com.signal.domain.monitoring.monitor;

import com.signal.domain.monitoring.entity.Detection;
import com.signal.domain.monitoring.repository.DetectionRepository;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 실제 AI 서버 연동 전까지 사용하는 스텁 구현체. 실제 검색(역이미지 검색 등)은 전혀 하지 않고,
 * 기준 이미지 URL의 해시로 결정론적인 가짜 탐지 결과(0~3건)를 생성한다. 출처 URL은 실존하지 않는
 * 시연용 주소(demo://)이며 응답 단계에서 simulated=true로 표시된다. 실서버 연동 시 이 클래스를 교체한다.
 *
 * MonitoringService(→FaceMonitor) 의존 방향의 순환을 피하기 위해 탐지 결과 반영 시
 * MonitoringService가 아닌 MonitoringRepository/DetectionRepository에 직접 접근한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "monitoring.mode", havingValue = "stub", matchIfMissing = true)
public class StubFaceMonitor implements FaceMonitor {

    private static final int MAX_INITIAL_DETECTIONS = 3;

    public static final String SIMULATED_URL_PREFIX = "demo://stub/";
    private static final String LEGACY_SIMULATED_URL_PREFIX = "https://stub.signal.local/";

    /** 외부 호스팅 없이 표시되는 "SAMPLE" 플레이스홀더 썸네일(data URI). */
    public static final String PLACEHOLDER_THUMBNAIL = "data:image/svg+xml;utf8,"
            + "<svg xmlns='http://www.w3.org/2000/svg' width='160' height='160'>"
            + "<rect width='160' height='160' fill='%23e5e7eb'/>"
            + "<text x='80' y='86' font-size='18' text-anchor='middle' fill='%236b7280'>SAMPLE</text></svg>";

    public static boolean isSimulatedUrl(String url) {
        return url != null
                && (url.startsWith(SIMULATED_URL_PREFIX) || url.startsWith(LEGACY_SIMULATED_URL_PREFIX));
    }

    private final MonitoringRepository monitoringRepository;
    private final DetectionRepository detectionRepository;
    private final long processingDelayMs;

    public StubFaceMonitor(MonitoringRepository monitoringRepository,
                            DetectionRepository detectionRepository,
                            @Value("${monitoring.processing-delay-ms:3000}") long processingDelayMs) {
        this.monitoringRepository = monitoringRepository;
        this.detectionRepository = detectionRepository;
        this.processingDelayMs = processingDelayMs;
    }

    @Override
    @Async
    public void startMonitoring(Long monitoringId, String referenceImageUrl) {
        try {
            log.warn("시연용 스텁 동작: 실제 얼굴 도용 검색을 하지 않고 예시 탐지 결과를 생성합니다. monitoringId={}",
                    monitoringId);
            Thread.sleep(processingDelayMs);

            if (monitoringRepository.findById(monitoringId).isEmpty()) {
                return;
            }

            int seed = Math.abs(referenceImageUrl.hashCode());
            int detectionCount = seed % (MAX_INITIAL_DETECTIONS + 1);

            for (int i = 0; i < detectionCount; i++) {
                detectionRepository.save(buildDetection(monitoringId, seed + i * 31));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("얼굴 모니터링 초기 탐지 실패: monitoringId={}", monitoringId, e);
        }
    }

    private Detection buildDetection(Long monitoringId, int detectionSeed) {
        String key = Integer.toHexString(Math.abs(detectionSeed));
        return Detection.builder()
                .monitoringId(monitoringId)
                .sourceUrl(SIMULATED_URL_PREFIX + "source/" + key)
                .thumbnailUrl(SIMULATED_URL_PREFIX + "thumbnail/" + key)
                .similarity(0.7 + (Math.abs(detectionSeed) % 30) / 100.0)
                .build();
    }
}
