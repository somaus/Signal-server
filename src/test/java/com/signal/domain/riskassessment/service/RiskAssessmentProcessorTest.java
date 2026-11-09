package com.signal.domain.riskassessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.riskassessment.analyzer.RiskAnalysisResult;
import com.signal.domain.riskassessment.analyzer.RiskAnalyzer;
import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.BoundingBox;
import com.signal.domain.riskassessment.entity.DetectedFace;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentProcessorTest {

    private static final byte[] CONTENT = {1, 2, 3};

    @Mock
    private RiskAnalyzer riskAnalyzer;

    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    private RiskAssessmentProcessor processor;
    private RiskAssessment assessment;

    @BeforeEach
    void setUp() {
        processor = new RiskAssessmentProcessor(riskAnalyzer, riskAssessmentRepository);
        assessment = RiskAssessment.processing(1L, null, "/uploads/risk-assessments/a.png");
        lenient().when(riskAssessmentRepository.findById(7L)).thenReturn(Optional.of(assessment));
    }

    @Test
    void 분석에_성공하면_결과를_채우고_COMPLETED로_저장한다() {
        RiskAnalysisResult result = new RiskAnalysisResult(
                true,
                RiskLevel.HIGH,
                80,
                List.of(RiskFactor.builder().type("IMAGE_QUALITY").label("이미지 품질").score(80).description("설명").build()),
                List.of("공개를 자제하세요."),
                List.of(DetectedFace.builder()
                        .faceIndex(0)
                        .boundingBox(BoundingBox.builder().x(1).y(2).width(3).height(4).build())
                        .build()),
                false);
        when(riskAnalyzer.analyze(any(MultipartFile.class))).thenReturn(result);

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.COMPLETED);
        assertThat(assessment.getFailureReason()).isNull();
        assertThat(assessment.getOverallRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessment.getOverallScore()).isEqualTo(80);
        assertThat(assessment.isFaceDetected()).isTrue();
        assertThat(assessment.getFactors()).hasSize(1);
        assertThat(assessment.getRecommendations()).containsExactly("공개를 자제하세요.");
        assertThat(assessment.getFaces()).hasSize(1);
        assertThat(assessment.isFallbackUsed()).isFalse();
        verify(riskAssessmentRepository).save(assessment);
    }

    @Test
    void 이미_TIMEOUT으로_정리된_건은_뒤늦게_분석이_끝나도_되살리지_않는다() {
        assessment.fail(AssessmentFailureReason.TIMEOUT);
        when(riskAnalyzer.analyze(any(MultipartFile.class)))
                .thenReturn(new RiskAnalysisResult(true, RiskLevel.HIGH, 80, List.of(), List.of(), List.of(), false));

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(assessment.getFailureReason()).isEqualTo(AssessmentFailureReason.TIMEOUT);
        assertThat(assessment.getOverallRiskLevel()).isNull();
        verify(riskAssessmentRepository, never()).save(any());
    }

    @Test
    void 분석기에는_업로드_바이트와_파일_정보가_그대로_전달된다() {
        when(riskAnalyzer.analyze(any(MultipartFile.class))).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(0);
            assertThat(file.getBytes()).containsExactly(CONTENT);
            assertThat(file.getContentType()).isEqualTo("image/png");
            assertThat(file.getOriginalFilename()).isEqualTo("photo.png");
            return RiskAnalysisResult.faceNotDetected(false);
        });

        processor.process(7L, "photo.png", "image/png", CONTENT);

        verify(riskAnalyzer).analyze(any(MultipartFile.class));
    }

    @Test
    void 얼굴이_검출되지_않으면_NO_FACE_DETECTED로_실패_처리한다() {
        when(riskAnalyzer.analyze(any(MultipartFile.class))).thenReturn(RiskAnalysisResult.faceNotDetected(false));

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(assessment.getFailureReason()).isEqualTo(AssessmentFailureReason.NO_FACE_DETECTED);
        assertThat(assessment.getOverallRiskLevel()).isNull();
        verify(riskAssessmentRepository).save(assessment);
    }

    @Test
    void 분석_불가면_AI_SERVER_ERROR로_실패_처리한다() {
        when(riskAnalyzer.analyze(any(MultipartFile.class)))
                .thenThrow(new SignalException(ErrorCode.ANALYSIS_UNAVAILABLE));

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(assessment.getFailureReason()).isEqualTo(AssessmentFailureReason.AI_SERVER_ERROR);
    }

    @Test
    void 분석_시간이_초과되면_TIMEOUT으로_실패_처리한다() {
        when(riskAnalyzer.analyze(any(MultipartFile.class)))
                .thenThrow(new SignalException(ErrorCode.ANALYSIS_TIMEOUT));

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(assessment.getFailureReason()).isEqualTo(AssessmentFailureReason.TIMEOUT);
    }

    @Test
    void 이미지가_거절되면_INVALID_IMAGE로_실패_처리한다() {
        when(riskAnalyzer.analyze(any(MultipartFile.class)))
                .thenThrow(new SignalException(ErrorCode.INVALID_INPUT));

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(assessment.getFailureReason()).isEqualTo(AssessmentFailureReason.INVALID_IMAGE);
    }

    @Test
    void 예상하지_못한_오류도_AI_SERVER_ERROR로_실패_처리한다() {
        when(riskAnalyzer.analyze(any(MultipartFile.class))).thenThrow(new IllegalStateException("boom"));

        processor.process(7L, "photo.png", "image/png", CONTENT);

        assertThat(assessment.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(assessment.getFailureReason()).isEqualTo(AssessmentFailureReason.AI_SERVER_ERROR);
    }

    @Test
    void 그_사이_기록이_사라졌으면_아무것도_저장하지_않는다() {
        when(riskAssessmentRepository.findById(8L)).thenReturn(Optional.empty());
        when(riskAnalyzer.analyze(any(MultipartFile.class))).thenReturn(RiskAnalysisResult.faceNotDetected(false));

        processor.process(8L, "photo.png", "image/png", CONTENT);

        verify(riskAssessmentRepository, never()).save(any());
    }
}
