package com.signal.domain.deepfakedetection.dto.response;

import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.ProvenanceSignal;
import com.signal.domain.deepfakedetection.entity.Verdict;
import java.util.List;

/**
 * 딥페이크 탐지 결과.
 *
 * 전용 탐지 모델(mode=http)로 분석한 결과에는 band(LOW, UNCERTAIN, HIGH, UNDETERMINED)와 모델 이름·버전이 채워진다.
 * 화면에는 퍼센트 대신 band를 "합성 가능성" 표현으로 보여 주는 것을 권장한다. band가 점수에서 나오지 않은 경우
 * (UNDETERMINED, assessmentBasis=PROVENANCE)에는 verdict, confidence, riskScore가 null이다.
 * 비전 LLM(mode=llm)이나 이전 기록은 band 등 새 필드가 null이고 provenanceSignals는 빈 목록이다.
 */
public record DeepfakeDetectionResponse(
        Long detectionId,
        DeepfakeDetectionStatus status,
        Verdict verdict,
        Double confidence,
        Integer riskScore,
        List<EvidenceResponse> evidences,
        String highlightedResultUrl,
        Boolean fallbackUsed,
        DetectionFailureReason failureReason,
        String band,
        String assessmentBasis,
        String undeterminedReason,
        String modelName,
        String modelVersion,
        List<ProvenanceSignalResponse> provenanceSignals,
        WebSearchResponse webSearch
) {

    public static DeepfakeDetectionResponse from(DeepfakeDetection detection) {
        return from(detection, null);
    }

    /** webSearch가 null이면 웹 검색 기능이 꺼져 있거나 이 탐지에는 검색이 없다(화면은 "준비 중"으로 둔다). */
    public static DeepfakeDetectionResponse from(DeepfakeDetection detection, WebSearch webSearch) {
        List<String> codes = detection.getProvenanceSignals() == null ? List.of() : detection.getProvenanceSignals();
        return new DeepfakeDetectionResponse(
                detection.getId(),
                detection.getStatus(),
                detection.getVerdict(),
                detection.getConfidence(),
                detection.getRiskScore(),
                detection.getEvidences().stream().map(EvidenceResponse::from).toList(),
                detection.getHighlightedResultUrl(),
                detection.getFallbackUsed(),
                detection.getFailureReason(),
                detection.getBand(),
                detection.getAssessmentBasis(),
                detection.getUndeterminedReason(),
                detection.getModelName(),
                detection.getModelVersion(),
                codes.stream()
                        .map(ProvenanceSignal::fromCode)
                        .flatMap(java.util.Optional::stream)
                        .map(ProvenanceSignalResponse::from)
                        .toList(),
                webSearch == null ? null : WebSearchResponse.from(webSearch));
    }
}
