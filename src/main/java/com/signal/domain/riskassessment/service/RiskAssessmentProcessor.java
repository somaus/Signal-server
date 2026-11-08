package com.signal.domain.riskassessment.service;

import com.signal.domain.riskassessment.analyzer.RiskAnalysisResult;
import com.signal.domain.riskassessment.analyzer.RiskAnalyzer;
import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.InMemoryMultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 접수된 위험도 분석을 백그라운드에서 수행하고 결과(또는 실패 사유)를 저장한다. 요청 스레드가 AI 응답을
 * 기다리며 붙잡혀 있지 않게 하려는 것이다(#30). 요청 언어는 AsyncConfig의 TaskDecorator가 넘겨 준다.
 *
 * 요청이 끝난 뒤에는 업로드 임시 파일을 쓸 수 없으므로 원본 MultipartFile이 아닌 바이트를 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskAssessmentProcessor {

    private final RiskAnalyzer riskAnalyzer;
    private final RiskAssessmentRepository riskAssessmentRepository;

    @Async
    public void process(Long assessmentId, String filename, String contentType, byte[] content) {
        try {
            RiskAnalysisResult result = riskAnalyzer.analyze(
                    new InMemoryMultipartFile("image", filename, contentType, content));
            if (!result.faceDetected()) {
                log.info("얼굴을 검출하지 못했습니다: assessmentId={}", assessmentId);
                markFailed(assessmentId, AssessmentFailureReason.NO_FACE_DETECTED);
                return;
            }
            markCompleted(assessmentId, result);
        } catch (SignalException e) {
            AssessmentFailureReason reason = e.getErrorCode() == ErrorCode.ANALYSIS_TIMEOUT
                    ? AssessmentFailureReason.TIMEOUT
                    : AssessmentFailureReason.AI_SERVER_ERROR;
            log.warn("위험도 분석 실패: assessmentId={}, reason={}", assessmentId, reason, e);
            markFailed(assessmentId, reason);
        } catch (Exception e) {
            log.error("위험도 분석 중 예상하지 못한 오류: assessmentId={}", assessmentId, e);
            markFailed(assessmentId, AssessmentFailureReason.AI_SERVER_ERROR);
        }
    }

    private void markCompleted(Long assessmentId, RiskAnalysisResult result) {
        riskAssessmentRepository.findById(assessmentId).ifPresent(assessment -> {
            assessment.complete(result.overallRiskLevel(), result.overallScore(), true, result.factors(),
                    result.recommendations(), result.faces(), result.fallbackUsed());
            riskAssessmentRepository.save(assessment);
        });
    }

    private void markFailed(Long assessmentId, AssessmentFailureReason reason) {
        riskAssessmentRepository.findById(assessmentId).ifPresent(assessment -> {
            assessment.fail(reason);
            riskAssessmentRepository.save(assessment);
        });
    }
}
