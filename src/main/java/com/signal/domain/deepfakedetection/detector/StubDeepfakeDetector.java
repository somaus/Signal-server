package com.signal.domain.deepfakedetection.detector;

import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.Evidence;
import com.signal.domain.deepfakedetection.entity.Region;
import com.signal.domain.deepfakedetection.entity.Verdict;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.file.FileStorage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

/**
 * 파일 바이트 해시로 verdict/riskScore/근거를 결정론적으로 산출하는 룰 기반 탐지기.
 * 원본 파일을 그대로 복제해 하이라이트 결과로 저장한다.
 *
 * 더 이상 Spring 빈으로 직접 등록되지 않는다({@link LlmDeepfakeDetector} 참고). LlmDeepfakeDetector가
 * 이 클래스를 내부적으로 (1) 영상 입력(비전 LLM 미지원) (2) LLM 미설정/호출 실패 시의 폴백으로 사용하며,
 * verdict 산출({@link #toVerdict})과 완료/실패 처리({@link #markCompleted}/{@link #markFailed})도 그대로 재사용한다.
 *
 * DeepfakeDetectionService(→DeepfakeDetector) 의존 방향의 순환을 피하기 위해 완료/실패
 * 처리 시 DeepfakeDetectionService가 아닌 DeepfakeDetectionRepository에 직접 접근한다.
 */
@Slf4j
public class StubDeepfakeDetector implements DeepfakeDetector {

    private static final List<String> EVIDENCE_TYPES = List.of(
            "FACIAL_ARTIFACT", "LIGHTING_INCONSISTENCY", "COMPRESSION_ARTIFACT", "BLINK_PATTERN_ANOMALY");
    private static final String HIGHLIGHTED_RESULT_DIRECTORY = "deepfake-detections";

    private final FileStorage fileStorage;
    private final DeepfakeDetectionRepository deepfakeDetectionRepository;
    private final long processingDelayMs;

    public StubDeepfakeDetector(FileStorage fileStorage,
                                 DeepfakeDetectionRepository deepfakeDetectionRepository,
                                 @Value("${deepfake-detection.processing-delay-ms:3000}") long processingDelayMs) {
        this.fileStorage = fileStorage;
        this.deepfakeDetectionRepository = deepfakeDetectionRepository;
        this.processingDelayMs = processingDelayMs;
    }

    @Override
    @Async
    public void detect(Long detectionId, String fileUrl, boolean isVideo) {
        try {
            Thread.sleep(processingDelayMs);

            byte[] content = fileStorage.load(fileUrl);
            int seed = Math.abs(Arrays.hashCode(content));

            int riskScore = seed % 101;
            Verdict verdict = toVerdict(riskScore);
            double confidence = Math.round((0.5 + (seed % 50) / 100.0) * 100) / 100.0;
            List<Evidence> evidences = buildEvidences(seed, isVideo);
            String highlightedResultUrl = fileStorage.store(content, HIGHLIGHTED_RESULT_DIRECTORY);

            // 이 클래스는 항상 룰 기반(해시 기반) 폴백 경로이므로 fallbackUsed는 항상 true.
            markCompleted(detectionId, verdict, confidence, riskScore, evidences, highlightedResultUrl, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(detectionId, DetectionFailureReason.AI_SERVER_ERROR);
        } catch (Exception e) {
            log.error("딥페이크 탐지 실패: detectionId={}", detectionId, e);
            markFailed(detectionId, DetectionFailureReason.AI_SERVER_ERROR);
        }
    }

    Verdict toVerdict(int riskScore) {
        if (riskScore < 30) {
            return Verdict.REAL;
        }
        if (riskScore <= 70) {
            return Verdict.SUSPICIOUS;
        }
        return Verdict.FAKE;
    }

    private List<Evidence> buildEvidences(int seed, boolean isVideo) {
        int count = 1 + seed % 3;
        List<Evidence> evidences = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int evidenceSeed = Math.abs(seed + i * 23);
            String type = EVIDENCE_TYPES.get(evidenceSeed % EVIDENCE_TYPES.size());
            Region region = Region.builder()
                    .x(evidenceSeed % 200)
                    .y(evidenceSeed % 150)
                    .width(50 + evidenceSeed % 100)
                    .height(50 + evidenceSeed % 100)
                    .build();
            Integer frame = isVideo ? evidenceSeed % 300 : null;

            evidences.add(Evidence.builder()
                    .type(type)
                    .description(toDescription(type))
                    .region(region)
                    .frame(frame)
                    .build());
        }
        return evidences;
    }

    private String toDescription(String type) {
        return switch (type) {
            case "FACIAL_ARTIFACT" -> "얼굴 경계에서 부자연스러운 합성 흔적이 발견되었습니다.";
            case "LIGHTING_INCONSISTENCY" -> "조명 방향과 그림자가 일치하지 않습니다.";
            case "COMPRESSION_ARTIFACT" -> "압축 아티팩트 패턴이 편집 흔적과 유사합니다.";
            case "BLINK_PATTERN_ANOMALY" -> "눈 깜빡임 패턴이 자연스럽지 않습니다.";
            default -> "이상 패턴이 발견되었습니다.";
        };
    }

    void markCompleted(Long detectionId, Verdict verdict, double confidence, int riskScore,
                        List<Evidence> evidences, String highlightedResultUrl, boolean fallbackUsed) {
        deepfakeDetectionRepository.findById(detectionId).ifPresent(detection -> {
            detection.complete(verdict, confidence, riskScore, evidences, highlightedResultUrl, fallbackUsed);
            deepfakeDetectionRepository.save(detection);
        });
    }

    void markFailed(Long detectionId, DetectionFailureReason failureReason) {
        deepfakeDetectionRepository.findById(detectionId).ifPresent(detection -> {
            detection.fail(failureReason);
            deepfakeDetectionRepository.save(detection);
        });
    }
}
