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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 신고서 서비스. 요약(LLM 호출, 최대 십수 초)과 PDF 생성은 DB 커넥션을 쥔 채로 하지 않는다. 그래서 이 둘이 끼는 메서드
 * (create/update/finalize/renderDocument)는 트랜잭션 없이 시작해, LLM 호출을 끝낸 뒤 짧은 쓰기 트랜잭션({@link #writeTx})에서만
 * 저장한다. open-in-view가 꺼져 있고 신고서의 컬렉션은 모두 EAGER라 트랜잭션 밖에서도 신고서를 그대로 읽을 수 있다.
 */
@Service
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
    private final TransactionTemplate writeTx;

    public ReportService(ReportRepository reportRepository, ReportEvidenceRepository reportEvidenceRepository,
                         ReportSummarizer reportSummarizer, ReportPdfRenderer reportPdfRenderer,
                         FileStorage fileStorage, PlatformTransactionManager transactionManager) {
        this.reportRepository = reportRepository;
        this.reportEvidenceRepository = reportEvidenceRepository;
        this.reportSummarizer = reportSummarizer;
        this.reportPdfRenderer = reportPdfRenderer;
        this.fileStorage = fileStorage;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Report createReport(Long userId, CreateReportRequest request) {
        validateRequired(request.description(), request.sourceUrls());
        validateEvidenceOwnership(userId, request.evidenceIds());

        // LLM 호출은 트랜잭션 밖에서 한다. 못 만들면 요약 없이 저장하고, 이후 수정·작성 완료 때 다시 시도한다.
        String summary = reportSummarizer.summarize(request.description(), request.damageType()).orElse(null);

        return writeTx.execute(status -> save(userId, request, summary));
    }

    private Report save(Long userId, CreateReportRequest request, String summary) {
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
        report.updateSummary(summary);

        return reportRepository.save(report);
    }

    public Report getReport(Long userId, Long reportId) {
        return getOwnedReport(userId, reportId);
    }

    public List<Report> getMyReports(Long userId) {
        return reportRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Report updateReport(Long userId, Long reportId, UpdateReportRequest request) {
        Report current = getOwnedReport(userId, reportId);
        validateDraft(current);
        String description = validatePatchDescription(request.getDescription());
        if (request.getEvidenceIds() != null) {
            validateEvidenceOwnership(userId, request.getEvidenceIds().orElse(null));
        }

        // 요약의 입력은 원문과 피해 유형이다. 둘 중 하나가 바뀌었거나, 이전에 요약을 못 만들어 비어 있으면 다시 만든다.
        String nextDescription = description != null ? description : current.getDescription();
        String nextDamageType = request.getDamageType() != null
                ? request.getDamageType().orElse(null) : current.getDamageType();
        boolean inputsChanged = !Objects.equals(nextDescription, current.getDescription())
                || !Objects.equals(nextDamageType, current.getDamageType());
        boolean needsSummary = inputsChanged || current.getSummary() == null;
        String summary = needsSummary
                ? reportSummarizer.summarize(nextDescription, nextDamageType).orElse(null) : null;

        return writeTx.execute(status -> {
            Report report = getOwnedReport(userId, reportId);
            validateDraft(report);
            report.applyPatch(
                    request.getIncidentDate(), request.getDiscoveryRoute(), request.getDamageType(),
                    description, request.getSourceUrls(), request.getEvidenceIds(),
                    request.getTargetAgencyType(), request.getAdditionalNotes());
            if (needsSummary) {
                // 요약을 만드는 동안 다른 요청이 입력을 바꿨다면 이 요약은 맞지 않으므로 붙이지 않는다(옛 요약도 남기지 않는다).
                boolean stillMatches = Objects.equals(report.getDescription(), nextDescription)
                        && Objects.equals(report.getDamageType(), nextDamageType);
                report.updateSummary(stillMatches ? summary : null);
            }
            return report;
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Report finalizeReport(Long userId, Long reportId) {
        Report current = getOwnedReport(userId, reportId);
        validateDraft(current);

        // 작성 완료한 신고서가 실제 제출 문서가 되므로, 요약이 비어 있으면 마지막으로 한 번 더 시도한다.
        String description = current.getDescription();
        String damageType = current.getDamageType();
        String retried = current.getSummary() == null
                ? reportSummarizer.summarize(description, damageType).orElse(null) : null;

        return writeTx.execute(status -> {
            Report report = getOwnedReport(userId, reportId);
            validateDraft(report);
            if (retried != null && report.getSummary() == null
                    && Objects.equals(report.getDescription(), description)
                    && Objects.equals(report.getDamageType(), damageType)) {
                report.updateSummary(retried);
            }
            report.markFinalized();
            return report;
        });
    }

    /** 신고 문서(PDF)를 신고서 내용으로 만들어 돌려준다. 본인 신고서만 가능하다. 생성하는 동안 DB 커넥션을 쥐지 않는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
