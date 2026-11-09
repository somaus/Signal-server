package com.signal.domain.report.dto.response;

import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportStatus;
import com.signal.domain.report.service.ReportDocuments;

public record ReportStatusResponse(Long reportId, ReportStatus status, String documentUrl) {

    public static ReportStatusResponse from(Report report) {
        return new ReportStatusResponse(report.getId(), report.getStatus(), ReportDocuments.pathFor(report.getId()));
    }
}
