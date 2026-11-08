package com.signal.domain.report.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.report.dto.response.ReportResponse;
import com.signal.domain.report.dto.response.ReportStatusResponse;
import com.signal.domain.report.entity.Report;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReportResponseTest {

    private Report reportWithId(Long id) {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        report.updateSummary("요약");
        ReflectionTestUtils.setField(report, "id", id);
        return report;
    }

    @Test
    void 신고_문서는_본인_인증이_필요한_다운로드_경로로_내려주고_요약도_포함한다() {
        ReportResponse response = ReportResponse.from(reportWithId(7L));

        assertThat(response.documentUrl()).isEqualTo("/api/v1/reports/7/document");
        assertThat(response.summary()).isEqualTo("요약");
    }

    @Test
    void 상태_응답도_같은_다운로드_경로를_준다() {
        ReportStatusResponse response = ReportStatusResponse.from(reportWithId(7L));

        assertThat(response.documentUrl()).isEqualTo("/api/v1/reports/7/document");
    }
}
