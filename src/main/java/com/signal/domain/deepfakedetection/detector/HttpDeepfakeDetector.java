package com.signal.domain.deepfakedetection.detector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.signal.domain.deepfakedetection.entity.DetectionBand;
import com.signal.domain.deepfakedetection.entity.AssessmentBasis;
import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.Evidence;
import com.signal.domain.deepfakedetection.entity.ProvenanceSignal;
import com.signal.domain.deepfakedetection.entity.Verdict;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.file.FileStorage;
import com.signal.global.http.InternalServiceClient;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 전용 탐지 모델 추론 서비스(detect-lab, FastAPI)의 {@code POST /detect}를 호출하는 탐지기.
 * {@code deepfake-detection.mode=http} 일 때만 활성화되며, 기본값(llm)에서는 {@link LlmDeepfakeDetector}가 사용된다.
 *
 * 점수(score, 0~1)는 모델이 낸 "가짜일 확률"이고, 등급({@link DetectionBand})은 {@link DetectionBandPolicy}가
 * 점수, 이미지 크기, 파일의 출처 신호({@link ProvenanceSignalExtractor})로 정한다. LLM은 판정에 관여하지 않는다.
 * 사용자에게는 퍼센트 대신 등급(합성 가능성 낮음, 불확실, 높음, 판단 불가)과 모델 이름·버전을 보여 준다.
 *
 * 하위 호환을 위해 verdict(LOW→REAL, UNCERTAIN→SUSPICIOUS, HIGH→FAKE)와 riskScore, confidence도 채운다.
 * 단, 등급이 점수에서 나오지 않은 경우(판단 불가, 출처 신호로 높임)에는 점수와 모순되지 않도록 셋 다 null이다.
 * confidence는 모델이 고른 쪽의 확률(max(score, 1 - score))이며 보정되지 않은 값이다.
 *
 * 이 모델은 얼굴 검출을 하지 않아 NO_FACE_DETECTED를 내지 않는다. 근거(evidences)는 {@link DeepfakeExplainer}가
 * 있을 때만, 이미 정해진 등급을 설명하는 용도로 채운다(없으면 빈 목록). 설명은 등급을 바꾸지 못하고 실패해도 결과에 영향이 없다.
 * 영상은 지원하지 않는다. 실패(연결 불가, 타임아웃, 비정상 응답, 범위 밖 점수)는 룰 기반 값으로 대체하지 않고
 * FAILED(분석하지 못함)로 처리한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deepfake-detection.mode", havingValue = "http")
public class HttpDeepfakeDetector implements DeepfakeDetector {

    private static final String HIGHLIGHTED_RESULT_DIRECTORY = "deepfake-detections";

    private final FileStorage fileStorage;
    private final DeepfakeDetectionRepository deepfakeDetectionRepository;
    private final StubDeepfakeDetector resultWriter;
    private final RestClient restClient;
    private final DetectionBandPolicy bandPolicy;
    private final DeepfakeExplainer explainer; // null이면 설명 없이 등급만 낸다

    @Autowired
    public HttpDeepfakeDetector(FileStorage fileStorage,
                                DeepfakeDetectionRepository deepfakeDetectionRepository,
                                RestClient.Builder restClientBuilder,
                                DetectionBandPolicy bandPolicy,
                                ObjectProvider<DeepfakeExplainer> explainer,
                                @Value("${deepfake-detection.http.base-url:http://localhost:8000}") String baseUrl,
                                @Value("${deepfake-detection.http.timeout-seconds:60}") long timeoutSeconds) {
        this(fileStorage, deepfakeDetectionRepository, InternalServiceClient.create(restClientBuilder, baseUrl, timeoutSeconds),
                bandPolicy, explainer.getIfAvailable());
    }

    HttpDeepfakeDetector(FileStorage fileStorage, DeepfakeDetectionRepository deepfakeDetectionRepository,
                         RestClient restClient) {
        this(fileStorage, deepfakeDetectionRepository, restClient, new DetectionBandPolicy(10, 70, 256), null);
    }

    HttpDeepfakeDetector(FileStorage fileStorage, DeepfakeDetectionRepository deepfakeDetectionRepository,
                         RestClient restClient, DetectionBandPolicy bandPolicy, DeepfakeExplainer explainer) {
        this.fileStorage = fileStorage;
        this.deepfakeDetectionRepository = deepfakeDetectionRepository;
        // 실패 기록은 기존 구현을 그대로 쓴다(지연은 쓰지 않는다).
        this.resultWriter = new StubDeepfakeDetector(fileStorage, deepfakeDetectionRepository, 0);
        this.restClient = restClient;
        this.bandPolicy = bandPolicy;
        this.explainer = explainer;
    }

    @Override
    @Async
    public void detect(Long detectionId, String fileUrl, boolean isVideo) {
        if (isVideo) {
            log.info("영상은 딥페이크 탐지를 지원하지 않습니다: detectionId={}", detectionId);
            resultWriter.markFailed(detectionId, DetectionFailureReason.VIDEO_NOT_SUPPORTED);
            return;
        }
        try {
            byte[] content = fileStorage.load(fileUrl);

            DetectResponse response = restClient.post()
                    .uri("/detect")
                    .body(InternalServiceClient.fileBody(content, InternalServiceClient.filenameOf(fileUrl)))
                    .retrieve()
                    .body(DetectResponse.class);

            double score = validatedScore(response);
            int riskScore = (int) Math.round(score * 100);
            int width = response.width() == null ? 0 : response.width();
            int height = response.height() == null ? 0 : response.height();

            List<ProvenanceSignal> signals = ProvenanceSignalExtractor.extract(content);
            DetectionBandPolicy.Assessment assessment = bandPolicy.assess(riskScore, width, height, signals);
            DetectionBand band = assessment.band();
            // 등급이 점수에서 나온 경우에만 점수 기반 숫자를 남긴다. 아니면 등급과 모순되는 숫자가 보이게 된다.
            boolean scoreBased = assessment.basis() == AssessmentBasis.MODEL && band != DetectionBand.UNDETERMINED;
            Verdict verdict = toVerdict(band);
            Double confidence = scoreBased ? Math.round(Math.max(score, 1 - score) * 100) / 100.0 : null;
            Integer risk = scoreBased ? riskScore : null;

            List<Evidence> evidences = explain(content, band, width, height);
            String highlightedResultUrl = fileStorage.store(content, "highlighted.png", HIGHLIGHTED_RESULT_DIRECTORY);

            log.info("딥페이크 탐지 완료: detectionId={}, model={}, modelVersion={}, score={}, band={}, basis={}, signals={}",
                    detectionId, response.model(), response.modelVersion(), score, band, assessment.basis(), signals);
            deepfakeDetectionRepository.findById(detectionId).ifPresent(detection -> {
                detection.completeAssessed(band, assessment.basis(), assessment.undeterminedReason(), verdict,
                        confidence, risk, evidences, highlightedResultUrl,
                        response.model(), response.modelVersion(), signals);
                deepfakeDetectionRepository.save(detection);
            });
        } catch (Exception e) {
            DetectionFailureReason reason = InternalServiceClient.isTimeout(e)
                    ? DetectionFailureReason.TIMEOUT
                    : DetectionFailureReason.AI_SERVER_ERROR;
            log.warn("딥페이크 탐지 서비스 호출 실패, 분석하지 못함으로 처리합니다: detectionId={}, reason={}",
                    detectionId, reason, e);
            resultWriter.markFailed(detectionId, reason);
        }
    }

    /** 등급을 verdict로 옮긴다(하위 호환). 판단 불가는 verdict가 없다. */
    private static Verdict toVerdict(DetectionBand band) {
        return switch (band) {
            case LOW -> Verdict.REAL;
            case UNCERTAIN -> Verdict.SUSPICIOUS;
            case HIGH -> Verdict.FAKE;
            case UNDETERMINED -> null;
        };
    }

    /** 설명은 덤이다. 실패하거나 없어도 탐지 결과는 그대로 나간다. */
    private List<Evidence> explain(byte[] content, DetectionBand band, int width, int height) {
        if (explainer == null) {
            return List.of();
        }
        try {
            return explainer.explain(content, sniffMimeType(content), band, width, height);
        } catch (Exception e) {
            log.warn("탐지 결과 설명 생성에 실패했습니다. 설명 없이 진행합니다.", e);
            return List.of();
        }
    }

    private static String sniffMimeType(byte[] content) {
        if (content.length >= 4
                && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "image/png";
        }
        return "image/jpeg";
    }

    private static double validatedScore(DetectResponse response) {
        if (response == null || response.score() == null) {
            throw new IllegalStateException("탐지 서비스 응답에 score가 없습니다.");
        }
        double score = response.score();
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalStateException("탐지 서비스 score가 0~1 범위를 벗어났습니다: " + score);
        }
        return score;
    }

    /** detect-lab의 POST /detect 응답. 모르는 필드는 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetectResponse(Double score, String model, String modelVersion, Integer width, Integer height) {
    }
}
