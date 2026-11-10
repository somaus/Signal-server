package com.signal.domain.report.controller;

import com.signal.domain.report.dto.request.CreateReportRequest;
import com.signal.domain.report.dto.request.UpdateReportRequest;
import com.signal.domain.report.dto.response.ReportEvidenceResponse;
import com.signal.domain.report.dto.response.ReportResponse;
import com.signal.domain.report.dto.response.ReportStatusResponse;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportEvidence;
import com.signal.domain.report.service.ReportService;
import com.signal.global.file.FileUrlSigner;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final FileUrlSigner fileUrlSigner;

    @PostMapping
    public ResponseEntity<ReportStatusResponse> createReport(
            @AuthenticationPrincipal Long userId,
            @RequestBody CreateReportRequest request) {
        Report report = reportService.createReport(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportStatusResponse.from(report));
    }

    @GetMapping
    public ResponseEntity<List<ReportResponse>> getMyReports(@AuthenticationPrincipal Long userId) {
        List<ReportResponse> reports = reportService.getMyReports(userId).stream()
                .map(ReportResponse::from)
                .toList();
        return ResponseEntity.ok(reports);
    }

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReportEvidenceResponse> uploadEvidence(
            @AuthenticationPrincipal Long userId,
            @RequestPart("file") MultipartFile file) {
        ReportEvidence evidence = reportService.uploadEvidence(userId, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportEvidenceResponse.from(evidence, fileUrlSigner));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<ReportResponse> getReport(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId) {
        Report report = reportService.getReport(userId, reportId);
        return ResponseEntity.ok(ReportResponse.from(report));
    }

    @DeleteMapping("/{reportId}")
    public ResponseEntity<Void> deleteReport(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId) {
        reportService.deleteReport(userId, reportId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{reportId}")
    public ResponseEntity<ReportResponse> updateReport(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId,
            @RequestBody UpdateReportRequest request) {
        Report report = reportService.updateReport(userId, reportId, request);
        return ResponseEntity.ok(ReportResponse.from(report));
    }

    /** 신고 문서(PDF). 파일로 저장하지 않고 요청할 때 신고서 내용으로 만든다. */
    @GetMapping("/{reportId}/document")
    public ResponseEntity<byte[]> getDocument(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId) {
        byte[] pdf = reportService.renderDocument(userId, reportId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("signal-report-" + reportId + ".pdf").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(pdf);
    }

    @PostMapping("/{reportId}/finalize")
    public ResponseEntity<ReportStatusResponse> finalizeReport(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long reportId) {
        Report report = reportService.finalizeReport(userId, reportId);
        return ResponseEntity.ok(ReportStatusResponse.from(report));
    }
}
