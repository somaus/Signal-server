package com.signal.domain.riskassessment.service;

import com.signal.domain.riskassessment.analyzer.RiskAnalysisResult;
import com.signal.domain.riskassessment.analyzer.RiskAnalyzer;
import com.signal.domain.riskassessment.analyzer.RiskResultAdjuster;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.domain.websearch.service.AssessmentSearchOutcome;
import com.signal.domain.websearch.service.WebSearchOwnerKey;
import com.signal.domain.websearch.service.WebSearchService;
import com.signal.global.file.InMemoryMultipartFile;
import com.signal.global.i18n.LanguageContext;
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
    private final WebSearchService webSearchService;

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
            AssessmentSearchOutcome search = searchIfConsented(assessmentId, content, result);
            if (search != null && search.isCompleted()) {
                result = RiskResultAdjuster.apply(result, search.sameImageCount(), search.similarImageCount(),
                        LanguageContext.current());
            }
            markCompleted(assessmentId, result, search);
        } catch (SignalException e) {
            AssessmentFailureReason reason = failureReasonOf(e.getErrorCode());
            log.warn("위험도 분석 실패: assessmentId={}, reason={}", assessmentId, reason, e);
            markFailed(assessmentId, reason);
        } catch (Exception e) {
            log.error("위험도 분석 중 예상하지 못한 오류: assessmentId={}", assessmentId, e);
            markFailed(assessmentId, AssessmentFailureReason.AI_SERVER_ERROR);
        }
    }

    private static AssessmentFailureReason failureReasonOf(ErrorCode errorCode) {
        return switch (errorCode) {
            case ANALYSIS_TIMEOUT -> AssessmentFailureReason.TIMEOUT;
            case FILE_TOO_LARGE, UNSUPPORTED_MEDIA_TYPE -> AssessmentFailureReason.INVALID_IMAGE;
            default -> AssessmentFailureReason.AI_SERVER_ERROR;
        };
    }

    /**
     * 사용자가 업로드할 때 동의했고 폴백 결과가 아니면 실제 웹 역검색을 한다. 동의하지 않았거나 검색 대상이 아니면 null이다.
     * 검색이 실패하거나 한도에 닿아도 분석 자체는 추정치 그대로 완료한다.
     */
    private AssessmentSearchOutcome searchIfConsented(Long assessmentId, byte[] content, RiskAnalysisResult result) {
        if (result.fallbackUsed()) {
            return null;
        }
        RiskAssessment assessment = riskAssessmentRepository.findById(assessmentId).orElse(null);
        if (assessment == null || !assessment.isWebSearchConsent()) {
            return null;
        }
        String ownerKey = assessment.getUserId() != null
                ? WebSearchOwnerKey.user(assessment.getUserId())
                : WebSearchOwnerKey.anonymous(assessment.getAnonymousId());
        try {
            return webSearchService.searchForAssessment(ownerKey, content);
        } catch (Exception e) {
            log.error("분석의 웹 검색 중 예상하지 못한 오류: assessmentId={}, 원인={}", assessmentId, e.toString());
            return AssessmentSearchOutcome.failed();
        }
    }

    private void markCompleted(Long assessmentId, RiskAnalysisResult result, AssessmentSearchOutcome search) {
        riskAssessmentRepository.findById(assessmentId).ifPresent(assessment -> {
            if (assessment.getStatus() != RiskAssessmentStatus.PROCESSING) {
                // 조회 시점에 이미 TIMEOUT으로 정리된 건이 뒤늦게 COMPLETED로 되살아나지 않게 한다.
                log.info("이미 마무리된 분석이라 결과를 버립니다: assessmentId={}, status={}", assessmentId,
                        assessment.getStatus());
                return;
            }
            assessment.complete(result.overallRiskLevel(), result.overallScore(), true, result.factors(),
                    result.recommendations(), result.faces(), result.fallbackUsed());
            if (search != null) {
                assessment.recordWebSearch(search.status(), search.reason());
            }
            riskAssessmentRepository.save(assessment);
        });
    }

    private void markFailed(Long assessmentId, AssessmentFailureReason reason) {
        riskAssessmentRepository.findById(assessmentId).ifPresent(assessment -> {
            if (assessment.getStatus() != RiskAssessmentStatus.PROCESSING) {
                return;
            }
            assessment.fail(reason);
            riskAssessmentRepository.save(assessment);
        });
    }
}
