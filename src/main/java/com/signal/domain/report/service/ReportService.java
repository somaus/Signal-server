package com.signal.domain.report.service;

import com.signal.domain.report.dto.request.CreateReportRequest;
import com.signal.domain.report.dto.request.UpdateReportRequest;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportEvidence;
import com.signal.domain.report.entity.ReportStatus;
import com.signal.domain.report.document.ReportPdfRenderer;
import com.signal.domain.report.repository.ReportEvidenceRepository;
import com.signal.domain.report.repository.ReportRepository;
import com.signal.domain.report.summary.ReportSummarizer;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import com.signal.global.file.UploadFileValidator;
import com.signal.global.util.TransactionUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private static final long MAX_EVIDENCE_FILE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EVIDENCE_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private static final String EVIDENCE_DIRECTORY = "report-evidences";

    private final ReportRepository reportRepository;
    private final ReportEvidenceRepository reportEvidenceRepository;
    private final ReportSummarizer reportSummarizer;
    private final ReportPdfRenderer reportPdfRenderer;
    private final FileStorage fileStorage;

    // 요약은 외부 LLM 호출(최대 ~15초)이라 DB 커넥션을 쥔 채 기다리지 않도록 트랜잭션 밖에서 만든다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Report createReport(Long userId, CreateReportRequest request) {
        validateRequired(request.description(), request.sourceUrls());
        validateEvidenceOwnership(userId, request.evidenceIds());

        Report report = Report.builder()
                .userId(userId)
                .incidentDate(request.incidentDate())
                .discoveryRoute(request.discoveryRoute())
                .damageType(request.damageType())
                .description(request.description())
                .sourceUrls(request.sourceUrls())
                .evidenceIds(request.evidenceIds() != null ? request.evidenceIds() : List.of())
                .targetAgencyType(request.targetAgencyType())
                .additionalNotes(request.additionalNotes())
                .build();
        report.updateSummary(reportSummarizer.summarize(report.getDescription(), report.getDamageType()).orElse(null));

        return reportRepository.save(report);
    }

    public Report getReport(Long userId, Long reportId) {
        return getOwnedReport(userId, reportId);
    }

    public List<Report> getMyReports(Long userId) {
        return reportRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public Report updateReport(Long userId, Long reportId, UpdateReportRequest request) {
        Report report = getOwnedReport(userId, reportId);
        validateDraft(report);
        String description = validatePatchDescription(request.getDescription());
        if (request.getEvidenceIds() != null) {
            validateEvidenceOwnership(userId, request.getEvidenceIds().orElse(null));
        }

        boolean descriptionChanged = description != null && !description.equals(report.getDescription());
        report.applyPatch(
                request.getIncidentDate(), request.getDiscoveryRoute(), request.getDamageType(),
                description, request.getSourceUrls(), request.getEvidenceIds(),
                request.getTargetAgencyType(), request.getAdditionalNotes());
        if (descriptionChanged) {
            // 원문이 바뀌면 옛 요약이 남지 않게 다시 만든다. 못 만들면 요약 없이 둔다.
            report.updateSummary(
                    reportSummarizer.summarize(report.getDescription(), report.getDamageType()).orElse(null));
        }

        return report;
    }

    @Transactional
    public Report finalizeReport(Long userId, Long reportId) {
        Report report = getOwnedReport(userId, reportId);
        validateDraft(report);

        report.markFinalized();

        return report;
    }

    /** 신고 문서(PDF)를 신고서 내용으로 만들어 돌려준다. 본인 신고서만 가능하다. */
    public byte[] renderDocument(Long userId, Long reportId) {
        return reportPdfRenderer.render(getOwnedReport(userId, reportId));
    }

    @Transactional
    public void deleteReport(Long userId, Long reportId) {
        Report report = getOwnedReport(userId, reportId);

        // 다른 신고서에서도 쓰는 증거는 남기고, 이 신고서에만 연결된 증거 파일을 함께 지운다.
        Set<Long> usedByOthers = new HashSet<>();
        reportRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(other -> !other.getId().equals(reportId))
                .forEach(other -> usedByOthers.addAll(other.getEvidenceIds()));
        Set<Long> onlyThisReport = new HashSet<>(report.getEvidenceIds());
        onlyThisReport.removeAll(usedByOthers);
        List<ReportEvidence> evidences = reportEvidenceRepository.findAllById(onlyThisReport).stream()
                .filter(evidence -> evidence.isOwnedBy(userId))
                .toList();

        reportRepository.delete(report);
        reportEvidenceRepository.deleteAll(evidences);

        TransactionUtils.runAfterCommit(() ->
                evidences.forEach(evidence -> fileStorage.delete(evidence.getFileUrl())));
    }

    @Transactional
    public ReportEvidence uploadEvidence(Long userId, MultipartFile file) {
        UploadFileValidator.validate(file, MAX_EVIDENCE_FILE_SIZE, ALLOWED_EVIDENCE_CONTENT_TYPES);

        String fileUrl = fileStorage.store(file, EVIDENCE_DIRECTORY);
        ReportEvidence evidence = ReportEvidence.builder()
                .userId(userId)
                .fileUrl(fileUrl)
                .build();

        return reportEvidenceRepository.save(evidence);
    }

    private Report getOwnedReport(Long userId, Long reportId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        if (!report.isOwnedBy(userId)) {
            throw new SignalException(ErrorCode.FORBIDDEN);
        }

        return report;
    }

    /** 신고서에는 본인이 업로드한 증거만 연결할 수 있다. */
    private void validateEvidenceOwnership(Long userId, List<Long> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return;
        }

        Set<Long> uniqueIds = new HashSet<>(evidenceIds);
        List<ReportEvidence> evidences = reportEvidenceRepository.findAllById(uniqueIds);
        if (evidences.size() != uniqueIds.size()) {
            throw new SignalException(ErrorCode.NOT_FOUND);
        }
        if (evidences.stream().anyMatch(evidence -> !evidence.isOwnedBy(userId))) {
            throw new SignalException(ErrorCode.FORBIDDEN);
        }
    }

    private void validateDraft(Report report) {
        if (report.getStatus() != ReportStatus.DRAFT) {
            throw new SignalException(ErrorCode.REPORT_ALREADY_FINALIZED);
        }
    }

    /** description은 필수값이라 PATCH로 지울 수 없다. 보내지 않았으면 null(변경 없음)을 반환한다. */
    private String validatePatchDescription(Optional<String> description) {
        if (description == null) {
            return null;
        }
        if (description.isEmpty() || !StringUtils.hasText(description.get())) {
            throw new SignalException(ErrorCode.REQUIRED_FIELD_MISSING);
        }
        return description.get();
    }

    private void validateRequired(String description, List<String> sourceUrls) {
        if (!StringUtils.hasText(description) || sourceUrls == null || sourceUrls.isEmpty()) {
            throw new SignalException(ErrorCode.REQUIRED_FIELD_MISSING);
        }
    }
}
