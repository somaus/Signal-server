package com.signal.domain.riskassessment.service;

import com.signal.global.file.TestFileBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentServiceTest {

    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;

    @Mock
    private RiskAssessmentProcessor riskAssessmentProcessor;

    @Mock
    private FileStorage fileStorage;

    private RiskAssessmentService riskAssessmentService;

    @BeforeEach
    void setUp() {
        riskAssessmentService = new RiskAssessmentService(riskAssessmentRepository, riskAssessmentProcessor, fileStorage);
    }

    private RiskAssessment completedAssessment(Long userId) {
        return RiskAssessment.builder()
                .userId(userId)
                .imageUrl("/uploads/risk-assessments/generated.png")
                .overallRiskLevel(RiskLevel.LOW)
                .overallScore(10)
                .faceDetected(true)
                .factors(List.of())
                .recommendations(List.of())
                .faces(List.of())
                .build();
    }

    @Test
    void 비로그인으로_내_분석_목록을_조회하면_UNAUTHORIZED이고_저장소를_조회하지_않는다() {
        assertThatThrownBy(() -> riskAssessmentService.getMyAssessments(null))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(riskAssessmentRepository);
    }

    @Test
    void 내_분석_목록은_완료된_분석만_조회한다() {
        RiskAssessment completed = completedAssessment(1L);
        when(riskAssessmentRepository.findByUserIdAndStatusOrderByCreatedAtDesc(1L, RiskAssessmentStatus.COMPLETED))
                .thenReturn(List.of(completed));

        assertThat(riskAssessmentService.getMyAssessments(1L)).containsExactly(completed);
    }

    @Test
    void 위험도_진단을_요청하면_PROCESSING으로_접수되고_분석은_백그라운드로_넘어간다() {
        byte[] bytes = TestFileBytes.PNG;
        MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", bytes);
        when(fileStorage.store(any(MockMultipartFile.class), anyString()))
                .thenReturn("/uploads/risk-assessments/generated.png");
        when(riskAssessmentRepository.save(any())).thenAnswer(invocation -> {
            RiskAssessment assessment = invocation.getArgument(0);
            ReflectionTestUtils.setField(assessment, "id", 7L);
            return assessment;
        });

        RiskAssessment saved = riskAssessmentService.createAssessment(1L, null, image);

        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getStatus()).isEqualTo(RiskAssessmentStatus.PROCESSING);
        assertThat(saved.getOverallRiskLevel()).isNull();
        assertThat(saved.getOverallScore()).isNull();
        assertThat(saved.getFailureReason()).isNull();
        assertThat(saved.getImageUrl()).isEqualTo("/uploads/risk-assessments/generated.png");
        verify(fileStorage).store(image, "risk-assessments");
        verify(riskAssessmentProcessor).process(eq(7L), eq("photo.png"), eq("image/png"), eq(bytes));
    }

    @Test
    void 비회원은_익명_식별자가_없으면_접수되지_않고_분석도_시작되지_않는다() {
        MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", TestFileBytes.PNG);

        assertThatThrownBy(() -> riskAssessmentService.createAssessment(null, " ", image))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANONYMOUS_ID_REQUIRED);

        verifyNoInteractions(riskAssessmentProcessor);
    }

    @Test
    void 비회원은_실패한_분석을_이용_횟수에서_제외하고_센다() {
        MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", TestFileBytes.PNG);
        when(riskAssessmentRepository.countByAnonymousIdAndStatusNot("anon-1", RiskAssessmentStatus.FAILED))
                .thenReturn(5L);

        assertThatThrownBy(() -> riskAssessmentService.createAssessment(null, "anon-1", image))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANONYMOUS_RISK_ASSESSMENT_LIMIT_EXCEEDED);

        verify(riskAssessmentRepository, never()).save(any());
        verifyNoInteractions(riskAssessmentProcessor);
    }

    @Test
    void 비회원은_5분이_지나도록_끝나지_않은_접수_건을_이용_횟수에서_뺀다() {
        MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", TestFileBytes.PNG);
        when(riskAssessmentRepository.countByAnonymousIdAndStatusNot("anon-1", RiskAssessmentStatus.FAILED))
                .thenReturn(5L);
        when(riskAssessmentRepository.countByAnonymousIdAndStatusAndCreatedAtBefore(
                eq("anon-1"), eq(RiskAssessmentStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(1L);
        when(riskAssessmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        riskAssessmentService.createAssessment(null, "anon-1", image);

        verify(riskAssessmentRepository).save(any());
    }

    @Test
    void 지원하지_않는_파일_형식이면_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("image", "malware.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> riskAssessmentService.createAssessment(1L, null, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 파일_크기가_10MB를_초과하면_예외가_발생한다() {
        byte[] tooLarge = new byte[(int) (10L * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("image", "big.png", "image/png", tooLarge);

        assertThatThrownBy(() -> riskAssessmentService.createAssessment(1L, null, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 존재하는_소유자가_조회하면_결과를_반환한다() {
        RiskAssessment riskAssessment = completedAssessment(1L);
        when(riskAssessmentRepository.findById(1L)).thenReturn(Optional.of(riskAssessment));

        RiskAssessment result = riskAssessmentService.getAssessment(1L, null, 1L);

        assertThat(result).isEqualTo(riskAssessment);
    }

    @Test
    void 처리_중인_분석은_PROCESSING_그대로_반환한다() {
        RiskAssessment processing = RiskAssessment.processing(1L, null, "/uploads/risk-assessments/a.png");
        when(riskAssessmentRepository.findById(1L)).thenReturn(Optional.of(processing));

        RiskAssessment result = riskAssessmentService.getAssessment(1L, null, 1L);

        assertThat(result.getStatus()).isEqualTo(RiskAssessmentStatus.PROCESSING);
        verify(riskAssessmentRepository, never()).save(any());
    }

    @Test
    void 오래_PROCESSING에_머문_분석은_조회할_때_시간초과_실패로_정리한다() {
        RiskAssessment stuck = RiskAssessment.processing(1L, null, "/uploads/risk-assessments/a.png");
        ReflectionTestUtils.setField(stuck, "createdAt", LocalDateTime.now().minusMinutes(6));
        when(riskAssessmentRepository.findById(1L)).thenReturn(Optional.of(stuck));
        when(riskAssessmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RiskAssessment result = riskAssessmentService.getAssessment(1L, null, 1L);

        assertThat(result.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(result.getFailureReason()).isEqualTo(AssessmentFailureReason.TIMEOUT);
        verify(riskAssessmentRepository).save(stuck);
    }

    @Test
    void 존재하지_않는_진단이면_예외가_발생한다() {
        when(riskAssessmentRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> riskAssessmentService.getAssessment(1L, null, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 소유자가_아니면_예외가_발생한다() {
        when(riskAssessmentRepository.findById(1L)).thenReturn(Optional.of(completedAssessment(2L)));

        assertThatThrownBy(() -> riskAssessmentService.getAssessment(1L, null, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }
}
