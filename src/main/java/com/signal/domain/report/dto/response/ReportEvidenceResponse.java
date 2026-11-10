package com.signal.domain.report.dto.response;

import com.signal.domain.report.entity.ReportEvidence;
import com.signal.global.file.FileUrlSigner;

public record ReportEvidenceResponse(Long evidenceId, String fileUrl) {

    public static ReportEvidenceResponse from(ReportEvidence evidence, FileUrlSigner signer) {
        return new ReportEvidenceResponse(evidence.getId(), signer.sign(evidence.getFileUrl()));
    }
}
