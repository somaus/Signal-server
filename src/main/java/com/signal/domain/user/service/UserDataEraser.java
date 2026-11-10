package com.signal.domain.user.service;

import com.signal.domain.agency.entity.AgencyConnection;
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
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportEvidence;
import com.signal.domain.report.repository.ReportEvidenceRepository;
import com.signal.domain.report.repository.ReportRepository;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.global.file.FileStorage;
import com.signal.global.util.TransactionUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 회원 탈퇴 때 그 회원의 기록과 업로드 파일을 모두 지운다(#43).
 *
 * 사용자 id는 각 도메인에서 FK 없는 일반 컬럼이라 users 행만 지우면 나머지가 그대로 남는다. 도메인마다
 * 흩어진 정리 규칙이 늘어나는 것을 막으려고 한곳에 모았다. DB 행은 호출한 트랜잭션 안에서 지우고,
 * 디스크의 파일은 커밋된 뒤에 지운다(롤백됐는데 파일만 사라지는 일을 막기 위해).
 *
 * 법령상 보존해야 하는 항목은 아직 확인되지 않아 모두 지운다. 보존 의무가 확인되면 이 클래스에서 분리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDataEraser {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final DeepfakeDetectionRepository deepfakeDetectionRepository;
    private final ProtectionRepository protectionRepository;
    private final MonitoringRepository monitoringRepository;
    private final DetectionRepository detectionRepository;
    private final ReportRepository reportRepository;
    private final ReportEvidenceRepository reportEvidenceRepository;
    private final AgencyConnectionRepository agencyConnectionRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final FileStorage fileStorage;

    @Transactional
    public void eraseAll(Long userId, String profileImageUrl) {
        Set<String> files = new LinkedHashSet<>();
        add(files, profileImageUrl);

        List<RiskAssessment> assessments = riskAssessmentRepository.findByUserId(userId);
        assessments.forEach(assessment -> add(files, assessment.getImageUrl()));
        riskAssessmentRepository.deleteAll(assessments);

        List<DeepfakeDetection> detections = deepfakeDetectionRepository.findByUserId(userId);
        detections.forEach(detection -> {
            add(files, detection.getFileUrl());
            add(files, detection.getHighlightedResultUrl());
        });
        deepfakeDetectionRepository.deleteAll(detections);

        List<Protection> protections = protectionRepository.findByUserId(userId);
        protections.forEach(protection -> {
            add(files, protection.getOriginalImageUrl());
            add(files, protection.getProtectedImageUrl());
        });
        protectionRepository.deleteAll(protections);

        List<Monitoring> monitorings = monitoringRepository.findByUserId(userId);
        monitorings.forEach(monitoring -> {
            add(files, monitoring.getReferenceImageUrl());
            detectionRepository.deleteAllByMonitoringId(monitoring.getId());
        });
        monitoringRepository.deleteAll(monitorings);

        List<Report> reports = reportRepository.findByUserIdOrderByCreatedAtDesc(userId);
        reportRepository.deleteAll(reports);

        List<ReportEvidence> evidences = reportEvidenceRepository.findByUserId(userId);
        evidences.forEach(evidence -> add(files, evidence.getFileUrl()));
        reportEvidenceRepository.deleteAll(evidences);

        List<AgencyConnection> connections = agencyConnectionRepository.findByUserId(userId);
        // attachedReportUrl은 지금은 신고 문서를 내려받는 API 경로(/api/...)라 지울 파일이 없다.
        // 문서를 파일로 저장하던 때의 기록은 업로드 경로(/uploads/...)를 갖고 있으므로 그 경우에만 파일을 지운다.
        connections.stream()
                .map(AgencyConnection::getAttachedReportUrl)
                .filter(url -> url != null && url.startsWith("/uploads/"))
                .forEach(url -> add(files, url));
        agencyConnectionRepository.deleteAll(connections);

        List<ChatSession> sessions = chatSessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        sessions.forEach(session -> chatMessageRepository.deleteByChatSessionId(session.getId()));
        chatSessionRepository.deleteAll(sessions);

        // 로그인 세션 기록에도 user_id가 남아 있으므로 함께 지운다.
        refreshTokenRepository.deleteByUserId(userId);

        TransactionUtils.runAfterCommit(() -> files.forEach(this::deleteQuietly));
    }

    private static void add(Set<String> files, String url) {
        if (StringUtils.hasText(url)) {
            files.add(url);
        }
    }

    /** 파일 하나를 못 지워도 나머지 정리와 탈퇴 자체는 계속되어야 하므로 예외를 삼키고 기록만 남긴다. */
    private void deleteQuietly(String url) {
        try {
            fileStorage.delete(url);
        } catch (RuntimeException e) {
            log.warn("탈퇴한 회원의 파일 삭제 실패: {}", url, e);
        }
    }
}
