package com.signal.domain.chat.dto.response;

import com.signal.domain.chat.progress.ConsultationStage;
import com.signal.domain.riskassessment.entity.RiskLevel;
import java.util.List;

/**
 * 상담 요약. recommendedSteps는 요청 언어로 번역된 표시용 문구이므로 값으로 분기하지 말고,
 * 같은 순서의 언어 중립 코드 recommendedStepCodes를 쓴다.
 * sessionEnded는 사용자가 상담 종료를 확인했는지이며, progress.status=COMPLETED(안내 단계를 모두 마침)와 다르다.
 */
public record ChatSummaryResponse(
        String situation,
        List<String> recommendedSteps,
        List<ConsultationStage> recommendedStepCodes,
        RiskLevel riskLevel,
        String riskDescription,
        int progressPercent,
        ConsultationProgressResponse progress,
        boolean sessionEnded
) {
}
