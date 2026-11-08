package com.signal.domain.riskassessment.dto.response;

import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.entity.RiskLevel;
import java.util.List;

/**
 * 위험도 분석 응답. 접수 직후(PROCESSING)에는 결과 필드가 비어 있고(overallRiskLevel, overallScore는 null),
 * 분석이 끝나면 COMPLETED로 채워진다. 실패하면 FAILED와 failureReason이 온다.
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
        boolean fallbackUsed
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
                riskAssessment.isFallbackUsed());
    }
}
