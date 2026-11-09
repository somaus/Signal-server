package com.signal.domain.riskassessment.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.riskassessment.entity.BoundingBox;
import com.signal.domain.riskassessment.entity.DetectedFace;
import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.ai.LlmJsonExtractor;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 비전 LLM({@link VisionCompletionClient}, 기본 구현은 Groq)으로 실제 이미지를 분석해 위험도를
 * 진단하는 분석기. risk-analysis.mode=llm(기본값)일 때의 RiskAnalyzer 빈이며, http면 HttpRiskAnalyzer가 대신 쓰인다.
 *
 * 안전/정확성을 위한 역할 분담:
 * - 이미지 품질·얼굴 노출도·역검색 노출 위험처럼 실제로 "봐야" 판단할 수 있는 항목은 비전 LLM이 평가한다.
 *   단, 역검색 노출 위험은 실제 역이미지 검색을 수행한 결과가 아니라 이미지 특성에 기반한 LLM의 추정치일 뿐이다.
 * - 메타데이터 노출(GPS 등)은 LLM에게 묻지 않고, EXIF를 직접 파싱해 결정론적으로 판정한다
 *   (LLM은 픽셀만 볼 뿐 원본 파일의 EXIF 바이트를 볼 수 없어 이 항목을 신뢰성 있게 답할 수 없다).
 * - 위험도별 권고 문구는 계속 {@link StubRiskAnalyzer}의 검증된 고정 문구를 재사용한다(LLM이 임의로
 *   지어내지 않도록).
 * - GROQ_API_KEY 미설정, vision.llm.enabled=false, API 호출/파싱 실패 시에는 해시 기반 추정 점수를
 *   내놓지 않고 ANALYSIS_UNAVAILABLE(분석하지 못함)로 응답한다.
 *
 * 주의: 범용 비전 LLM은 전문 위험도 분석 모델이 아니므로 점수·얼굴 위치는 참고용 추정치다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "risk-analysis.mode", havingValue = "llm", matchIfMissing = true)
public class LlmRiskAnalyzer implements RiskAnalyzer {

    private static final String SYSTEM_PROMPT = """
            당신은 'Signal' 서비스에서 사용자가 SNS에 올리기 전 사진의 위험 요소를 진단하는 비전 분석
            어시스턴트입니다. 목표는 이 사진이 딥페이크 합성이나 얼굴 도용에 악용될 위험이 얼마나 되는지
            평가하는 것입니다.

            - 이미지에 사람 얼굴이 없으면 faceDetected를 false로 하고 나머지 숫자 필드는 0으로 채우세요.
            - reverseSearchRiskScore는 실제 역이미지 검색을 수행한 결과가 아니라, 배경·특징의 독특함에
              기반한 추정치입니다. 이 점을 note에도 자연스럽게 반영하세요.
            - 각 note는 반드시 같은 항목의 score와 앞뒤가 맞아야 합니다. score가 낮은데 note는 위험한
              것처럼 쓰거나, score가 높은데 note는 괜찮다는 식으로 쓰지 마세요. note는 그 score를 준
              구체적인 이유를 설명해야 합니다.
            - 아래 JSON 형식으로만 답하세요. 코드블록이나 다른 설명 없이 순수 JSON만 출력하세요.

            {
              "faceDetected": true 또는 false,
              "faceBox": {"x":0-1,"y":0-1,"width":0-1,"height":0-1} 형태의 정규화 좌표 또는 얼굴이 없으면 null,
              "imageQualityScore": 0~100 정수 (해상도·선명도가 높아 합성에 악용되기 쉬울수록 높은 점수),
              "imageQualityNote": "한국어 1문장 설명",
              "faceExposureScore": 0~100 정수 (얼굴이 정면으로 뚜렷하게 노출될수록 높은 점수),
              "faceExposureNote": "한국어 1문장 설명",
              "reverseSearchRiskScore": 0~100 정수,
              "reverseSearchRiskNote": "한국어 1문장 설명"
            }
            """;

    private final StubRiskAnalyzer fallbackAnalyzer = new StubRiskAnalyzer();
    private final VisionCompletionClient visionCompletionClient;
    private final ObjectMapper objectMapper;
    private final boolean llmEnabled;

    public LlmRiskAnalyzer(VisionCompletionClient visionCompletionClient,
                            ObjectMapper objectMapper,
                            @Value("${vision.llm.enabled:true}") boolean llmEnabled) {
        this.visionCompletionClient = visionCompletionClient;
        this.objectMapper = objectMapper;
        this.llmEnabled = llmEnabled;
    }

    @Override
    public RiskAnalysisResult analyze(MultipartFile image) {
        if (!llmEnabled) {
            throw new SignalException(ErrorCode.ANALYSIS_UNAVAILABLE);
        }
        try {
            return analyzeWithLlm(image);
        } catch (Exception e) {
            log.warn("LLM 위험도 분석 실패, 분석하지 못함으로 응답합니다.", e);
            throw new SignalException(isTimeout(e) ? ErrorCode.ANALYSIS_TIMEOUT : ErrorCode.ANALYSIS_UNAVAILABLE);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private RiskAnalysisResult analyzeWithLlm(MultipartFile image) throws IOException {
        byte[] content = image.getBytes();
        int[] pixelSize = readPixelSize(content);
        boolean hasGpsMetadata = GpsMetadata.present(content);

        ResponseLanguage language = LanguageContext.current();
        String userPrompt = buildUserPrompt(pixelSize);
        String rawResponse = visionCompletionClient.complete(
                content, image.getContentType(), SYSTEM_PROMPT + language.promptInstruction(), userPrompt);
        VisionRiskAssessment parsed = parseResponse(rawResponse);

        if (!parsed.faceDetected()) {
            log.warn("비전 LLM이 얼굴을 검출하지 못했다고 응답했습니다. 응답: {}", rawResponse);
            return RiskAnalysisResult.faceNotDetected(false);
        }

        List<RiskFactor> factors = buildFactors(parsed, hasGpsMetadata, language);
        int overallScore = (int) Math.round(
                factors.stream().mapToInt(RiskFactor::getScore).average().orElse(0));
        RiskLevel riskLevel = RiskLevel.fromScore(overallScore);
        List<String> recommendations = fallbackAnalyzer.buildRecommendations(riskLevel);
        DetectedFace face = buildFace(parsed, pixelSize);

        return new RiskAnalysisResult(true, riskLevel, overallScore, factors, recommendations, List.of(face), false);
    }

    private List<RiskFactor> buildFactors(
            VisionRiskAssessment parsed, boolean hasGpsMetadata, ResponseLanguage language) {
        int metadataLeakScore = hasGpsMetadata ? 85 : 10;
        String metadataLeakDescription = RiskTexts.metadataDescription(hasGpsMetadata, language);

        return List.of(
                RiskFactor.builder()
                        .type("IMAGE_QUALITY")
                        .label(RiskTexts.label(RiskTexts.IMAGE_QUALITY, language, "이미지 품질"))
                        .score(clampScore(parsed.imageQualityScore()))
                        .description(withScore(parsed.imageQualityNote(), clampScore(parsed.imageQualityScore()), language))
                        .build(),
                RiskFactor.builder()
                        .type("FACE_EXPOSURE")
                        .label(RiskTexts.label(RiskTexts.FACE_EXPOSURE, language, "얼굴 노출도"))
                        .score(clampScore(parsed.faceExposureScore()))
                        .description(withScore(parsed.faceExposureNote(), clampScore(parsed.faceExposureScore()), language))
                        .build(),
                RiskFactor.builder()
                        .type("METADATA_LEAK")
                        .label(RiskTexts.label(RiskTexts.METADATA_LEAK, language, "메타데이터 노출"))
                        .score(metadataLeakScore)
                        .description(withScore(metadataLeakDescription, metadataLeakScore, language))
                        .build(),
                RiskFactor.builder()
                        .type("REVERSE_SEARCH_RISK")
                        .label(RiskTexts.label(RiskTexts.REVERSE_SEARCH_RISK, language, "역검색 노출 위험"))
                        .score(clampScore(parsed.reverseSearchRiskScore()))
                        .description(withScore(parsed.reverseSearchRiskNote(), clampScore(parsed.reverseSearchRiskScore()), language))
                        .build());
    }

    private String withScore(String note, int score, ResponseLanguage language) {
        String safeNote = (note == null || note.isBlank()) ? RiskTexts.defaultNote(language) : note.trim();
        return RiskTexts.withScore(safeNote, score, language);
    }

    private DetectedFace buildFace(VisionRiskAssessment parsed, int[] pixelSize) {
        int imgWidth = pixelSize[0];
        int imgHeight = pixelSize[1];
        FaceBox box = parsed.faceBox();

        // LLM이 좌표를 못 주거나 실제 픽셀 크기를 읽지 못했으면 박스를 지어내지 않고 비워 둔다(위치 알 수 없음).
        BoundingBox boundingBox = null;
        if (box != null && imgWidth > 0 && imgHeight > 0) {
            boundingBox = BoundingBox.builder()
                    .x(toPixel(box.x(), imgWidth))
                    .y(toPixel(box.y(), imgHeight))
                    .width(Math.max(1, toPixel(box.width(), imgWidth)))
                    .height(Math.max(1, toPixel(box.height(), imgHeight)))
                    .build();
        }

        return DetectedFace.builder()
                .faceIndex(0)
                .boundingBox(boundingBox)
                .build();
    }

    private int toPixel(double normalized, int dimension) {
        double clamped = Math.max(0.0, Math.min(1.0, normalized));
        return (int) Math.round(clamped * dimension);
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String buildUserPrompt(int[] pixelSize) {
        if (pixelSize[0] > 0 && pixelSize[1] > 0) {
            return "이 이미지의 실제 해상도는 " + pixelSize[0] + "x" + pixelSize[1] + " 픽셀입니다. "
                    + "이 이미지를 분석해서 시스템 프롬프트에 명시한 JSON 형식으로만 답해주세요.";
        }
        return "이 이미지를 분석해서 시스템 프롬프트에 명시한 JSON 형식으로만 답해주세요.";
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

    private VisionRiskAssessment parseResponse(String rawResponse) {
        try {
            String json = LlmJsonExtractor.stripCodeFence(rawResponse);
            return objectMapper.readValue(json, VisionRiskAssessment.class);
        } catch (Exception e) {
            throw new SignalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private record VisionRiskAssessment(
            boolean faceDetected,
            FaceBox faceBox,
            int imageQualityScore,
            String imageQualityNote,
            int faceExposureScore,
            String faceExposureNote,
            int reverseSearchRiskScore,
            String reverseSearchRiskNote) {
    }

    private record FaceBox(double x, double y, double width, double height) {
    }
}
