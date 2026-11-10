package com.signal.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.agency.entity.AgencyConnection;
import com.signal.domain.agency.entity.ConnectionType;
import com.signal.domain.agency.repository.AgencyConnectionRepository;
import com.signal.domain.auth.repository.RefreshTokenRepository;
import com.signal.domain.chat.entity.ChatSession;
import com.signal.domain.chat.repository.ChatMessageRepository;
import com.signal.domain.chat.repository.ChatSessionRepository;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.repository.DetectionRepository;
import com.signal.domain.monitoring.repository.MonitoringRepository;
import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportEvidence;
import com.signal.domain.report.repository.ReportEvidenceRepository;
import com.signal.domain.report.repository.ReportRepository;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.file.FileStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserDataEraserTest {

    private static final long USER_ID = 1L;

    @Mock
    private RiskAssessmentRepository riskAssessmentRepository;
    @Mock
    private DeepfakeDetectionRepository deepfakeDetectionRepository;
    @Mock
    private ProtectionRepository protectionRepository;
    @Mock
    private MonitoringRepository monitoringRepository;
    @Mock
    private DetectionRepository detectionRepository;
    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportEvidenceRepository reportEvidenceRepository;
    @Mock
    private AgencyConnectionRepository agencyConnectionRepository;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private FileStorage fileStorage;

    private UserDataEraser eraser;

    private RiskAssessment assessment;
    private DeepfakeDetection detection;
    private Protection protection;
    private Monitoring monitoring;
    private Report report;
    private ReportEvidence evidence;
    private AgencyConnection connection;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        eraser = new UserDataEraser(riskAssessmentRepository, deepfakeDetectionRepository, protectionRepository,
                monitoringRepository, detectionRepository, reportRepository, reportEvidenceRepository,
                agencyConnectionRepository, chatSessionRepository, chatMessageRepository, refreshTokenRepository, fileStorage);

        assessment = RiskAssessment.builder().userId(USER_ID).imageUrl("/uploads/risk-assessments/a.png")
                .faceDetected(true).factors(List.of()).recommendations(List.of()).faces(List.of()).build();
        detection = DeepfakeDetection.builder().userId(USER_ID).fileUrl("/uploads/deepfake-detections/d.png").build();
        ReflectionTestUtils.setField(detection, "highlightedResultUrl", "/uploads/deepfake-detections/h.png");
        protection = Protection.builder().userId(USER_ID).assessmentId(10L).protectionLevel(ProtectionLevel.NORMAL)
                // 보호 처리의 원본은 위험도 분석에 올린 사진과 같은 파일이다.
                .originalImageUrl("/uploads/risk-assessments/a.png").build();
        protection.complete("/uploads/protections/p.png", 0.01);
        monitoring = Monitoring.builder().userId(USER_ID).referenceImageUrl("/uploads/monitoring/m.png").build();
        ReflectionTestUtils.setField(monitoring, "id", 9L);
        report = Report.builder().userId(USER_ID).description("설명").sourceUrls(List.of()).evidenceIds(List.of())
                .build();
        evidence = ReportEvidence.builder().userId(USER_ID).fileUrl("/uploads/report-evidences/e.png").build();
        connection = AgencyConnection.builder().userId(USER_ID).agencyId(3L).connectionType(ConnectionType.WEB)
                .attachedReportUrl("/uploads/reports/c.pdf").build();
        session = ChatSession.builder().sessionId("s-1").userId(USER_ID).saveConsent(true).build();
        ReflectionTestUtils.setField(session, "id", 5L);

        when(riskAssessmentRepository.findByUserId(USER_ID)).thenReturn(List.of(assessment));
        when(deepfakeDetectionRepository.findByUserId(USER_ID)).thenReturn(List.of(detection));
        when(protectionRepository.findByUserId(USER_ID)).thenReturn(List.of(protection));
        when(monitoringRepository.findByUserId(USER_ID)).thenReturn(List.of(monitoring));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(report));
        when(reportEvidenceRepository.findByUserId(USER_ID)).thenReturn(List.of(evidence));
        when(agencyConnectionRepository.findByUserId(USER_ID)).thenReturn(List.of(connection));
        when(chatSessionRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(session));
    }

    @Test
    void 탈퇴하면_모든_도메인의_기록을_지운다() {
        eraser.eraseAll(USER_ID, null);

        verify(riskAssessmentRepository).deleteAll(List.of(assessment));
        verify(deepfakeDetectionRepository).deleteAll(List.of(detection));
        verify(protectionRepository).deleteAll(List.of(protection));
        verify(detectionRepository).deleteAllByMonitoringId(9L);
        verify(monitoringRepository).deleteAll(List.of(monitoring));
        verify(refreshTokenRepository).deleteByUserId(USER_ID);
        verify(reportRepository).deleteAll(List.of(report));
        verify(reportEvidenceRepository).deleteAll(List.of(evidence));
        verify(agencyConnectionRepository).deleteAll(List.of(connection));
        verify(chatMessageRepository).deleteByChatSessionId(5L);
        verify(chatSessionRepository).deleteAll(List.of(session));
    }

    @Test
    void 탈퇴하면_연결된_업로드_파일을_한_번씩만_지운다() {
        eraser.eraseAll(USER_ID, "/uploads/profile/me.png");

        verify(fileStorage).delete("/uploads/profile/me.png");
        // 위험도 분석 사진과 보호 처리 원본은 같은 파일이라 한 번만 지운다.
        verify(fileStorage, times(1)).delete("/uploads/risk-assessments/a.png");
        verify(fileStorage).delete("/uploads/deepfake-detections/d.png");
        verify(fileStorage).delete("/uploads/deepfake-detections/h.png");
        verify(fileStorage).delete("/uploads/protections/p.png");
        verify(fileStorage).delete("/uploads/monitoring/m.png");
        verify(fileStorage).delete("/uploads/report-evidences/e.png");
        verify(fileStorage).delete("/uploads/reports/c.pdf");
    }

    @Test
    void 기관_연결의_신고_문서_주소가_API_경로이면_파일로_지우려_하지_않는다() {
        AgencyConnection apiPath = AgencyConnection.builder().userId(USER_ID).agencyId(4L)
                .connectionType(ConnectionType.WEB).attachedReportUrl("/api/v1/reports/5/document").build();
        when(agencyConnectionRepository.findByUserId(USER_ID)).thenReturn(List.of(connection, apiPath));

        eraser.eraseAll(USER_ID, null);

        verify(fileStorage, never()).delete("/api/v1/reports/5/document");
        verify(fileStorage).delete("/uploads/reports/c.pdf");   // 옛 기록(파일로 저장하던 때)은 그대로 지운다
        verify(agencyConnectionRepository).deleteAll(List.of(connection, apiPath));
    }

    @Test
    void 프로필_이미지와_파일_주소가_없으면_삭제를_시도하지_않는다() {
        when(protectionRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(riskAssessmentRepository.findByUserId(USER_ID)).thenReturn(List.of());

        eraser.eraseAll(USER_ID, " ");

        verify(fileStorage, times(0)).delete(" ");
    }

    @Test
    void 파일_하나를_못_지워도_나머지를_계속_지운다() {
        doThrow(new IllegalStateException("디스크 오류")).when(fileStorage).delete("/uploads/risk-assessments/a.png");

        assertThatCode(() -> eraser.eraseAll(USER_ID, null)).doesNotThrowAnyException();

        verify(fileStorage).delete("/uploads/monitoring/m.png");
        verify(fileStorage).delete("/uploads/report-evidences/e.png");
        assertThat(true).isTrue();
    }
}
