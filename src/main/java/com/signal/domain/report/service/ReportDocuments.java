package com.signal.domain.report.service;

/**
 * 신고 문서(PDF)는 파일로 저장하지 않고 요청할 때 신고서 내용으로 만든다. 그 문서를 받는 API 경로.
 * 공개 업로드 경로(/uploads)에 두지 않고 본인 인증이 필요한 API로만 내려준다.
 */
public final class ReportDocuments {

    private ReportDocuments() {
    }

    public static String pathFor(Long reportId) {
        return "/api/v1/reports/" + reportId + "/document";
    }
}
