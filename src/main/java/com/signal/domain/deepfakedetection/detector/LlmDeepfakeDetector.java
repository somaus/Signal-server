package com.signal.domain.deepfakedetection.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.Evidence;
import com.signal.domain.deepfakedetection.entity.Region;
import com.signal.domain.deepfakedetection.entity.Verdict;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.ai.LlmJsonExtractor;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.file.FileStorage;
import com.signal.global.i18n.LanguageContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 비전 LLM({@link VisionCompletionClient}, 기본 구현은 Groq)으로 실제 이미지를 분석해 딥페이크
 * 여부를 판정하는 탐지기. 기본(deepfake-detection.mode=llm)에서 쓰이는 Spring 빈이며, mode=http이면
 * {@link HttpDeepfakeDetector}가 대신한다.
 *
 * 중요한 한계(정직하게 명시):
 * - 범용 비전 LLM은 전문 딥페이크 탐지 모델이 아니다. 학습 데이터에 실제/합성 이진 분류 태스크가
 *   포함되어 있지 않아, 근거를 그럴듯하게 대며 오판할 수 있다. 가짜(해시 기반 난수)보다는 낫지만
 *   전문 탐지기 수준의 정확도를 기대해서는 안 된다.
 * - 영상 입력은 지원하지 않는다. Groq의 비전 API는 정지 이미지만 받으며, 프레임 추출·다중 프레임
 *   분석에는 별도 비디오 처리 파이프라인(ffmpeg 등)이 필요해 이번 범위에는 포함하지 않았다.
 *   영상이 들어오면 분석하지 못함(FAILED)으로 처리한다.
 * - highlightedResultUrl은 스텁과 마찬가지로 원본 파일을 그대로 저장한다. 근거 영역을 실제로
 *   시각적으로 표시(하이라이트 오버레이 렌더링)하는 기능은 이번 범위에 포함하지 않았다.
 *
 * GROQ_API_KEY 미설정, vision.llm.enabled=false, API 호출/파싱 실패 시에는 예외를 삼키고
 * 파일 해시로 만든 추정 판정을 내놓는 대신 FAILED(분석하지 못함)로 처리한다. 딥페이크처럼 민감한
 * 주제에서 근거 없는 verdict를 보여주는 것보다 분석하지 못했다고 알리는 편이 안전하기 때문이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deepfake-detection.mode", havingValue = "llm", matchIfMissing = true)
public class LlmDeepfakeDetector implements DeepfakeDetector {

    private static final String SYSTEM_PROMPT = """
            당신은 'Signal' 서비스의 딥페이크(AI 합성) 이미지 탐지를 보조하는 비전 분석 어시스턴트입니다.
            주어진 이미지를 보고 AI로 생성되었거나 합성·편집된 흔적이 있는지 분석하세요.

            중요:
            - 당신은 전문 딥페이크 탐지 모델이 아니라 범용 비전 언어 모델입니다. 확신이 없으면 SUSPICIOUS로
              판단하고, 근거가 명확하지 않은데 FAKE로 단정하지 마세요.
            - verdict/confidence/riskScore를 먼저 정하고, evidences는 반드시 그 판단과 앞뒤가 맞아야
              합니다. verdict가 REAL이거나 riskScore가 30 미만이면 evidences는 반드시 빈 배열로
              답하세요. "눈 깜빡임", "조명", "압축 아티팩트" 같은 항목을 하나씩 형식적으로 채우려고
              사소하거나 애매한 특징을 근거처럼 서술하지 마세요 — 실제로 합성·조작을 의심할 만큼
              뚜렷한 흔적이 있을 때만, 그리고 그 근거가 verdict/riskScore를 실제로 뒷받침할 때만
              evidences에 담으세요.
            - 아래 JSON 형식으로만 답하세요. 코드블록이나 다른 설명 없이 순수 JSON만 출력하세요.

            - 이미지에 사람 얼굴이 하나도 없으면 faceDetected를 false로 하고, 나머지 필드는 verdict "REAL",
              confidence 0, riskScore 0, evidences 빈 배열로 채우세요.

            {
              "faceDetected": true 또는 false,
              "verdict": "REAL" 또는 "SUSPICIOUS" 또는 "FAKE",
              "confidence": 0.0~1.0 사이 소수 (당신 판단의 확신도),
              "riskScore": 0~100 사이 정수 (높을수록 합성·조작 가능성이 높음),
              "evidences": [
                {
                  "type": "FACIAL_ARTIFACT" 또는 "LIGHTING_INCONSISTENCY" 또는 "COMPRESSION_ARTIFACT"
                          또는 "BLINK_PATTERN_ANOMALY" 또는 "OTHER",
                  "description": "한국어로 구체적인 근거 설명 (1문장)",
                  "region": {"x":0-1,"y":0-1,"width":0-1,"height":0-1} 형태의 정규화 좌표,
                            특정 부위를 지목할 수 없으면 null
                }
              ]
            }
            근거가 없으면 evidences는 빈 배열로 답하세요. 최대 5개까지만 포함하세요.
            """;
    private static final String USER_PROMPT = "이 이미지를 분석해서 시스템 프롬프트에 명시한 JSON 형식으로만 답해주세요.";
    private static final String HIGHLIGHTED_RESULT_DIRECTORY = "deepfake-detections";
    private static final int MAX_EVIDENCES = 5;

    private final FileStorage fileStorage;
    private final DeepfakeDetectionRepository deepfakeDetectionRepository;
    private final StubDeepfakeDetector fallbackDetector;
    private final VisionCompletionClient visionCompletionClient;
    private final ObjectMapper objectMapper;
    private final boolean llmEnabled;

    public LlmDeepfakeDetector(FileStorage fileStorage,
                                DeepfakeDetectionRepository deepfakeDetectionRepository,
                                VisionCompletionClient visionCompletionClient,
                                ObjectMapper objectMapper,
                                @Value("${deepfake-detection.processing-delay-ms:3000}") long processingDelayMs,
                                @Value("${vision.llm.enabled:true}") boolean llmEnabled) {
        this.fileStorage = fileStorage;
        this.deepfakeDetectionRepository = deepfakeDetectionRepository;
        this.visionCompletionClient = visionCompletionClient;
        this.objectMapper = objectMapper;
        this.fallbackDetector = new StubDeepfakeDetector(fileStorage, deepfakeDetectionRepository, processingDelayMs);
        this.llmEnabled = llmEnabled;
    }

    @Override
    @Async
    public void detect(Long detectionId, String fileUrl, boolean isVideo) {
        if (isVideo) {
            log.info("영상은 딥페이크 탐지를 지원하지 않습니다: detectionId={}", detectionId);
            fallbackDetector.markFailed(detectionId, DetectionFailureReason.VIDEO_NOT_SUPPORTED);
            return;
        }
        if (!llmEnabled) {
            log.info("비전 LLM이 비활성화되어 딥페이크 탐지를 수행하지 않습니다: detectionId={}", detectionId);
            fallbackDetector.markFailed(detectionId, DetectionFailureReason.AI_SERVER_ERROR);
            return;
        }
        try {
            byte[] content = fileStorage.load(fileUrl);
            int[] pixelSize = readPixelSize(content);
            String mimeType = sniffMimeType(content);

            String rawResponse = visionCompletionClient.complete(
                    content, mimeType, SYSTEM_PROMPT + LanguageContext.current().promptInstruction(), USER_PROMPT);
            VisionDeepfakeAssessment parsed = parseResponse(rawResponse);
            if (Boolean.FALSE.equals(parsed.faceDetected())) {
                log.info("비전 LLM이 얼굴을 검출하지 못했습니다: detectionId={}", detectionId);
                fallbackDetector.markFailed(detectionId, DetectionFailureReason.NO_FACE_DETECTED);
                return;
            }

            Verdict verdict = toVerdict(parsed.verdict(), parsed.riskScore());
            double confidence = clampConfidence(parsed.confidence());
            int riskScore = clampScore(parsed.riskScore());
            List<Evidence> evidences = toEvidences(parsed.evidences(), pixelSize);
            String highlightedResultUrl = fileStorage.store(content, HIGHLIGHTED_RESULT_DIRECTORY);

            // 실제 비전 LLM 응답을 그대로 반영하는 경로이므로 fallbackUsed는 false.
            fallbackDetector.markCompleted(
                    detectionId, verdict, confidence, riskScore, evidences, highlightedResultUrl, false);
        } catch (Exception e) {
            DetectionFailureReason reason = isTimeout(e)
                    ? DetectionFailureReason.TIMEOUT
                    : DetectionFailureReason.AI_SERVER_ERROR;
            log.warn("LLM 딥페이크 탐지 실패, 분석하지 못함으로 처리합니다: detectionId={}, reason={}", detectionId, reason, e);
            fallbackDetector.markFailed(detectionId, reason);
        }
    }

    private boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private Verdict toVerdict(String rawVerdict, int riskScore) {
        if (rawVerdict != null) {
            try {
                return Verdict.valueOf(rawVerdict.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 아래 riskScore 기반 폴백으로 진행
            }
        }
        return fallbackDetector.toVerdict(clampScore(riskScore));
    }

    private List<Evidence> toEvidences(List<VisionEvidence> rawEvidences, int[] pixelSize) {
        if (rawEvidences == null || rawEvidences.isEmpty()) {
            return List.of();
        }
        List<Evidence> evidences = new ArrayList<>();
        for (VisionEvidence raw : rawEvidences) {
            if (evidences.size() >= MAX_EVIDENCES) {
                break;
            }
            if (raw == null || raw.description() == null || raw.description().isBlank()) {
                continue;
            }
            evidences.add(Evidence.builder()
                    .type(normalizeType(raw.type()))
                    .description(raw.description().trim())
                    .region(toRegion(raw.region(), pixelSize))
                    .frame(null)
                    .build());
        }
        return evidences;
    }

    private String normalizeType(String type) {
        return (type == null || type.isBlank()) ? "OTHER" : type.trim().toUpperCase();
    }

    /**
     * RegionResponse.from()이 null 체크 없이 바로 getX() 등을 호출하므로, region은 절대 null을
     * 반환하지 않는다 — LLM이 위치를 안 주거나 픽셀 크기를 못 읽으면 이미지 중심 영역으로 근사한다.
     */
    private Region toRegion(VisionRegion region, int[] pixelSize) {
        int imgWidth = pixelSize[0];
        int imgHeight = pixelSize[1];
        if (region != null && imgWidth > 0 && imgHeight > 0) {
            return Region.builder()
                    .x(toPixel(region.x(), imgWidth))
                    .y(toPixel(region.y(), imgHeight))
                    .width(Math.max(1, toPixel(region.width(), imgWidth)))
                    .height(Math.max(1, toPixel(region.height(), imgHeight)))
                    .build();
        }
        int fallbackWidth = Math.max(imgWidth, 200);
        int fallbackHeight = Math.max(imgHeight, 200);
        return Region.builder()
                .x(fallbackWidth / 4)
                .y(fallbackHeight / 4)
                .width(fallbackWidth / 2)
                .height(fallbackHeight / 2)
                .build();
    }

    private int toPixel(double normalized, int dimension) {
        double clamped = Math.max(0.0, Math.min(1.0, normalized));
        return (int) Math.round(clamped * dimension);
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private double clampConfidence(double confidence) {
        double rounded = Math.round(confidence * 100) / 100.0;
        return Math.max(0.0, Math.min(1.0, rounded));
    }

    private int[] readPixelSize(byte[] content) {
        try {
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(content));
            if (bufferedImage == null) {
                return new int[]{0, 0};
            }
            return new int[]{bufferedImage.getWidth(), bufferedImage.getHeight()};
        } catch (Exception e) {
            log.warn("이미지 픽셀 크기 판독 실패", e);
            return new int[]{0, 0};
        }
    }

    private String sniffMimeType(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            return "image/png";
        }
        return "image/jpeg";
    }

    private VisionDeepfakeAssessment parseResponse(String rawResponse) throws Exception {
        String json = LlmJsonExtractor.stripCodeFence(rawResponse);
        return objectMapper.readValue(json, VisionDeepfakeAssessment.class);
    }

    private record VisionDeepfakeAssessment(
            Boolean faceDetected,
            String verdict,
            double confidence,
            int riskScore,
            List<VisionEvidence> evidences) {
    }

    private record VisionEvidence(String type, String description, VisionRegion region) {
    }

    private record VisionRegion(double x, double y, double width, double height) {
    }
}
