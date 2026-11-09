package com.signal.domain.riskassessment.analyzer;

import com.signal.domain.riskassessment.entity.BoundingBox;
import com.signal.domain.riskassessment.entity.DetectedFace;
import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartFile;

/**
 * 얼굴 검출 모델(YuNet)과 이미지 측정으로 위험도를 진단하는 분석기. {@code risk-analysis.mode=http} 일 때만 활성화되며,
 * 기본값(llm)에서는 {@link LlmRiskAnalyzer}가 사용된다.
 *
 * <p>LLM이 점수를 추정하는 {@link LlmRiskAnalyzer}와 달리 여기서는 측정과 점수가 모두 결정론적이다.
 * risk-lab(FastAPI)이 얼굴 위치와 크기, 선명도, 정면도를 재고, 서버가 {@link RiskScoring}의 고정 공식으로 점수를 낸다.
 * 항목 설명에는 점수 근거가 된 측정값(얼굴 픽셀 크기, 면적 비율 등)을 그대로 적는다. 메타데이터 노출(GPS)은
 * 파일의 EXIF를 직접 읽어 판정한다.
 *
 * <p>역검색 노출 위험(REVERSE_SEARCH_RISK)은 실제로 역이미지 검색을 해 보지 않고는 잴 수 없어, 이 모드에서는 항목에서 뺀다
 * (LLM 추정치를 측정값처럼 보이게 하지 않는다). 얼굴은 면적이 큰 순서로 최대 {@value #MAX_FACES}개까지 돌려준다.
 * 호출 실패, 타임아웃, 비정상 응답은 임의의 점수로 대체하지 않고 분석하지 못함으로 응답한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "risk-analysis.mode", havingValue = "http")
public class HttpRiskAnalyzer implements RiskAnalyzer {

    static final int MAX_FACES = 10;

    private final StubRiskAnalyzer recommendationSource = new StubRiskAnalyzer();
    private final RestClient restClient;

    @Autowired
    public HttpRiskAnalyzer(RestClient.Builder restClientBuilder,
                            @Value("${risk-analysis.http.base-url:http://localhost:8000}") String baseUrl,
                            @Value("${risk-analysis.http.timeout-seconds:20}") long timeoutSeconds) {
        this(buildClient(restClientBuilder, baseUrl, timeoutSeconds));
    }

    HttpRiskAnalyzer(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildClient(RestClient.Builder builder, String baseUrl, long timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public RiskAnalysisResult analyze(MultipartFile image) {
        try {
            byte[] content = image.getBytes();
            RiskMeasurement measurement = measure(content, image.getOriginalFilename());
            return toResult(measurement, GpsMetadata.present(content), LanguageContext.current());
        } catch (SignalException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            // 400(디코딩 실패)·413(해상도 초과)은 이미지 문제라 재시도해도 같다.
            log.info("위험도 측정 서비스가 이미지를 거절했습니다: status={}", e.getStatusCode());
            throw new SignalException(ErrorCode.INVALID_INPUT);
        } catch (Exception e) {
            log.warn("위험도 측정 서비스 호출 실패, 분석하지 못함으로 응답합니다.", e);
            throw new SignalException(isTimeout(e) ? ErrorCode.ANALYSIS_TIMEOUT : ErrorCode.ANALYSIS_UNAVAILABLE);
        }
    }

    private RiskMeasurement measure(byte[] content, String originalFilename) throws IOException {
        String filename = originalFilename == null || originalFilename.isBlank() ? "image" : originalFilename;
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        RiskMeasurement measurement = restClient.post()
                .uri("/analyze")
                .body(body)
                .retrieve()
                .body(RiskMeasurement.class);
        if (measurement == null) {
            throw new IllegalStateException("위험도 측정 서비스 응답이 비어 있습니다.");
        }
        return measurement;
    }

    RiskAnalysisResult toResult(RiskMeasurement measurement, boolean hasGps, ResponseLanguage language) {
        List<RiskMeasurement.Face> valid = measurement.facesOrEmpty().stream()
                .filter(f -> f.box() != null && f.box().width() > 0 && f.box().height() > 0)
                .sorted(Comparator.comparingInt((RiskMeasurement.Face f) -> f.box().width() * f.box().height()).reversed())
                .toList();
        int detectedCount = valid.size();
        List<RiskMeasurement.Face> faces = valid.stream().limit(MAX_FACES).toList();
        if (faces.isEmpty()) {
            log.info("얼굴이 검출되지 않았습니다: model={}/{}", measurement.model(), measurement.modelVersion());
            return RiskAnalysisResult.faceNotDetected(false);
        }

        // 항목 점수는 가장 높은 얼굴 기준이다(사진에 여러 명이 있으면 가장 위험한 한 명이 사진의 위험이다).
        RiskMeasurement.Face quality = faces.stream()
                .max(Comparator.comparingInt(RiskScoring::imageQuality)).orElseThrow();
        RiskMeasurement.Face exposure = faces.stream()
                .max(Comparator.comparingInt(RiskScoring::faceExposure)).orElseThrow();
        int qualityScore = RiskScoring.imageQuality(quality);
        int exposureScore = RiskScoring.faceExposure(exposure);
        int metadataScore = hasGps ? 85 : 10;

        List<RiskFactor> factors = List.of(
                factor(RiskTexts.IMAGE_QUALITY, "이미지 품질", qualityScore,
                        RiskTexts.measuredQuality(orZero(quality.facePx()), orZero(quality.sharpness()), language), language),
                factor(RiskTexts.FACE_EXPOSURE, "얼굴 노출도", exposureScore,
                        RiskTexts.measuredExposure(detectedCount, orZero(exposure.areaRatio()) * 100,
                                orZero(exposure.frontal()) * 100, language), language),
                factor(RiskTexts.METADATA_LEAK, "메타데이터 노출", metadataScore,
                        RiskTexts.metadataDescription(hasGps, language), language));

        int overallScore = (int) Math.round(factors.stream().mapToInt(RiskFactor::getScore).average().orElse(0));
        RiskLevel level = RiskLevel.fromScore(overallScore);
        List<DetectedFace> detected = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) {
            RiskMeasurement.Box box = faces.get(i).box();
            detected.add(DetectedFace.builder()
                    .faceIndex(i)
                    .boundingBox(BoundingBox.builder()
                            .x(box.x()).y(box.y()).width(box.width()).height(box.height()).build())
                    .build());
        }
        log.info("위험도 분석 완료: model={}/{}, faces={}/{}, quality={}, exposure={}, metadata={}, overall={}",
                measurement.model(), measurement.modelVersion(), faces.size(), detectedCount, qualityScore, exposureScore,
                metadataScore, overallScore);
        return new RiskAnalysisResult(true, level, overallScore, factors,
                recommendationSource.buildRecommendations(level), detected, false);
    }

    private static RiskFactor factor(String type, String fallbackLabel, int score, String note, ResponseLanguage language) {
        return RiskFactor.builder()
                .type(type)
                .label(RiskTexts.label(type, language, fallbackLabel))
                .score(score)
                .description(RiskTexts.withScore(note, score, language))
                .build();
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
