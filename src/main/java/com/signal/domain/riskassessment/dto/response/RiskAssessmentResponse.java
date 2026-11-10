package com.signal.domain.riskassessment.dto.response;

import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.domain.websearch.entity.UnavailableReason;
import com.signal.domain.websearch.entity.WebSearchStatus;
import java.util.List;

/**
 * 위험도 분석 응답. 접수 직후(PROCESSING)에는 결과 필드가 비어 있고(overallRiskLevel, overallScore는 null),
 * 분석이 끝나면 COMPLETED로 채워진다. 실패하면 FAILED와 failureReason이 온다.
 * webSearchStatus는 사용자가 웹 역검색에 동의했을 때만 있고(null이면 동의하지 않았거나 아직 모름), COMPLETED면 검색 결과가
 * 점수에 반영됐고, UNAVAILABLE(webSearchUnavailableReason)·FAILED면 추정치 그대로다.
 */
public record RiskAssessmentResponse(
        Long assessmentId,
        RiskAssessmentStatus status,
        AssessmentFailureReason failureReason,
        RiskLevel overallRiskLevel,
        Integer overallScore,
        List<RiskFactorResponse> factors,
        List<String> recommendations,
        boolean faceDetected,
        List<FaceResponse> faces,
        boolean fallbackUsed,
        WebSearchStatus webSearchStatus,
        UnavailableReason webSearchUnavailableReason
) {

    public static RiskAssessmentResponse from(RiskAssessment riskAssessment) {
        return new RiskAssessmentResponse(
                riskAssessment.getId(),
                riskAssessment.getStatus(),
                riskAssessment.getFailureReason(),
                riskAssessment.getOverallRiskLevel(),
                riskAssessment.getOverallScore(),
                riskAssessment.getFactors().stream().map(RiskFactorResponse::from).toList(),
                riskAssessment.getRecommendations(),
                riskAssessment.isFaceDetected(),
                riskAssessment.getFaces().stream().map(FaceResponse::from).toList(),
                riskAssessment.isFallbackUsed(),
                riskAssessment.getWebSearchStatus(),
                riskAssessment.getWebSearchUnavailableReason());
    }
}
