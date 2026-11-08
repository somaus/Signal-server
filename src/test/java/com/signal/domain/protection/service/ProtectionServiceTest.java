package com.signal.domain.protection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.entity.ProtectionStatus;
import com.signal.domain.protection.protector.ImageProtector;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.domain.riskassessment.service.RiskAssessmentService;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtectionServiceTest {

    @Mock
    private ProtectionRepository protectionRepository;

    @Mock
    private RiskAssessmentService riskAssessmentService;

    @Mock
    private ImageProtector imageProtector;

    @Mock
    private FileStorage fileStorage;

    private ProtectionService protectionService;

    @BeforeEach
    void setUp() {
        protectionService = new ProtectionService(protectionRepository, riskAssessmentService, imageProtector, fileStorage);
    }

    private RiskAssessment sampleRiskAssessment() {
        return RiskAssessment.builder()
                .userId(1L)
                .imageUrl("/uploads/risk-assessments/original.png")
                .overallRiskLevel(RiskLevel.LOW)
                .overallScore(10)
                .faceDetected(true)
                .factors(List.of())
                .recommendations(List.of())
                .faces(List.of())
                .build();
    }

    @Test
    void 분석이_끝나지_않은_진단은_보호할_수_없다() {
        when(riskAssessmentService.getAssessment(1L, null, 10L))
                .thenReturn(com.signal.domain.riskassessment.entity.RiskAssessment.processing(1L, null, "/uploads/a.png"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> protectionService.createProtection(1L, 10L, ProtectionLevel.NORMAL))
                .isInstanceOf(com.signal.global.exception.SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", com.signal.global.exception.ErrorCode.CONFLICT);

        org.mockito.Mockito.verifyNoInteractions(imageProtector);
    }

    @Test
    void 보호_요청을_생성하면_PROCESSING_상태로_저장되고_비동기_처리가_시작된다() {
        when(riskAssessmentService.getAssessment(1L, null, 10L)).thenReturn(sampleRiskAssessment());
        when(protectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Protection protection = protectionService.createProtection(1L, 10L, ProtectionLevel.STRONG);

        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.PROCESSING);
        assertThat(protection.getProtectionLevel()).isEqualTo(ProtectionLevel.STRONG);
        assertThat(protection.getOriginalImageUrl()).isEqualTo("/uploads/risk-assessments/original.png");
        verify(imageProtector).protect(eq(protection.getId()), eq("/uploads/risk-assessments/original.png"), eq(ProtectionLevel.STRONG));
    }

    @Test
    void 보호수준을_지정하지_않으면_기본값_NORMAL이_적용된다() {
        when(riskAssessmentService.getAssessment(1L, null, 10L)).thenReturn(sampleRiskAssessment());
        when(protectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Protection protection = protectionService.createProtection(1L, 10L, null);

        assertThat(protection.getProtectionLevel()).isEqualTo(ProtectionLevel.NORMAL);
        verify(imageProtector).protect(any(), any(), eq(ProtectionLevel.NORMAL));
    }

    @Test
    void 존재하지_않거나_소유자가_아닌_진단이면_예외가_발생하고_처리가_시작되지_않는다() {
        when(riskAssessmentService.getAssessment(1L, null, 10L)).thenThrow(new SignalException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> protectionService.createProtection(1L, 10L, null))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(imageProtector, never()).protect(any(), any(), any());
    }

    @Test
    void 존재하는_소유자가_조회하면_결과를_반환한다() {
        Protection protection = Protection.builder()
                .userId(1L).assessmentId(10L).protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl("/uploads/risk-assessments/original.png").build();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));

        Protection result = protectionService.getProtection(1L, 1L);

        assertThat(result).isEqualTo(protection);
    }

    @Test
    void 존재하지_않는_보호요청이면_예외가_발생한다() {
        when(protectionRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> protectionService.getProtection(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 소유자가_아니면_조회시_예외가_발생한다() {
        Protection protection = Protection.builder()
                .userId(2L).assessmentId(10L).protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl("/uploads/risk-assessments/original.png").build();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));

        assertThatThrownBy(() -> protectionService.getProtection(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void 완료된_보호이미지는_다운로드할_수_있다() {
        Protection protection = Protection.builder()
                .userId(1L).assessmentId(10L).protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl("/uploads/risk-assessments/original.png").build();
        protection.complete("/uploads/protections/generated.png", 0.03);
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load("/uploads/protections/generated.png")).thenReturn(new byte[]{1, 2, 3});

        byte[] result = protectionService.downloadProtectedImage(1L, 1L);

        assertThat(result).containsExactly(1, 2, 3);
    }

    @Test
    void 처리가_완료되지_않았으면_다운로드시_예외가_발생한다() {
        Protection protection = Protection.builder()
                .userId(1L).assessmentId(10L).protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl("/uploads/risk-assessments/original.png").build();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));

        assertThatThrownBy(() -> protectionService.downloadProtectedImage(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PROTECTION_NOT_READY);
    }
}
