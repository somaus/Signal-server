package com.signal.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.signal.domain.report.dto.request.CreateReportRequest;
import com.signal.domain.report.dto.request.UpdateReportRequest;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportStatus;
import com.signal.domain.report.entity.TimelineEvent;
import com.signal.domain.report.document.ReportPdfRenderer;
import com.signal.domain.report.summary.ReportSummarizer;
import com.signal.domain.report.entity.ReportEvidence;
import com.signal.domain.report.repository.ReportEvidenceRepository;
import com.signal.domain.report.repository.ReportRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ReportEvidenceRepository reportEvidenceRepository;

    @Mock
    private ReportSummarizer reportSummarizer;

    @Mock
    private ReportPdfRenderer reportPdfRenderer;

    @Mock
    private FileStorage fileStorage;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService(
                reportRepository, reportEvidenceRepository, reportSummarizer, reportPdfRenderer, fileStorage);
    }

    private CreateReportRequest sampleRequest() {
        return new CreateReportRequest(
                LocalDate.of(2026, 7, 1),
                "지인 제보",
                "딥페이크 합성물 유포",
                "SNS에 합성 이미지가 유포되었습니다.",
                List.of("https://example.com/post/1"),
                List.of(10L, 11L),
                "경찰청",
                "가해자 계정 정보도 함께 전달 부탁드립니다.");
    }

    @Test
    void 신고서를_생성하면_DRAFT_상태로_저장되고_요약이_붙는다() {
        when(reportSummarizer.summarize(any(), any())).thenReturn(Optional.of("신고인은 SNS에 합성 이미지가 올라온 것을 알게 되었다."));
        when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportEvidenceRepository.findAllById(any())).thenReturn(List.of(
                ReportEvidence.builder().userId(1L).fileUrl("/uploads/a.png").build(),
                ReportEvidence.builder().userId(1L).fileUrl("/uploads/b.png").build()));

        Report report = reportService.createReport(1L, sampleRequest());

        assertThat(report.getUserId()).isEqualTo(1L);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(report.getSummary()).isEqualTo("신고인은 SNS에 합성 이미지가 올라온 것을 알게 되었다.");
        assertThat(report.getAdditionalNotes()).isEqualTo("가해자 계정 정보도 함께 전달 부탁드립니다.");
        assertThat(report.getTimeline()).hasSize(1);
        assertThat(report.getTimeline().get(0).getEvent()).isEqualTo(TimelineEvent.CREATED);
    }

    @Test
    void 다른_사용자의_증거를_연결하면_FORBIDDEN_예외가_발생한다() {
        when(reportEvidenceRepository.findAllById(any())).thenReturn(List.of(
                ReportEvidence.builder().userId(1L).fileUrl("/uploads/a.png").build(),
                ReportEvidence.builder().userId(2L).fileUrl("/uploads/b.png").build()));

        assertThatThrownBy(() -> reportService.createReport(1L, sampleRequest()))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_증거를_연결하면_NOT_FOUND_예외가_발생한다() {
        when(reportEvidenceRepository.findAllById(any())).thenReturn(List.of(
                ReportEvidence.builder().userId(1L).fileUrl("/uploads/a.png").build()));

        assertThatThrownBy(() -> reportService.createReport(1L, sampleRequest()))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void 수정시에도_다른_사용자의_증거를_연결하면_FORBIDDEN_예외가_발생한다() {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(reportEvidenceRepository.findAllById(any())).thenReturn(List.of(
                ReportEvidence.builder().userId(2L).fileUrl("/uploads/b.png").build()));

        UpdateReportRequest request = new UpdateReportRequest(null, null, null, null, null, Optional.of(List.of(99L)), null, null);

        assertThatThrownBy(() -> reportService.updateReport(1L, 1L, request))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void 설명이_없으면_REQUIRED_FIELD_MISSING_예외가_발생한다() {
        CreateReportRequest request = new CreateReportRequest(
                null, null, null, " ", List.of("https://example.com/post/1"), null, null, null);

        assertThatThrownBy(() -> reportService.createReport(1L, request))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    void sourceUrls가_없으면_REQUIRED_FIELD_MISSING_예외가_발생한다() {
        CreateReportRequest request = new CreateReportRequest(
                null, null, null, "설명입니다.", List.of(), null, null, null);

        assertThatThrownBy(() -> reportService.createReport(1L, request))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    void 소유자가_조회하면_결과를_반환한다() {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        Report result = reportService.getReport(1L, 1L);

        assertThat(result).isEqualTo(report);
    }

    @Test
    void 존재하지_않는_신고서면_예외가_발생한다() {
        when(reportRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getReport(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 소유자가_아니면_예외가_발생한다() {
        Report report = Report.builder()
                .userId(2L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.getReport(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void null이_아닌_필드만_반영되고_설명이_바뀌면_요약이_다시_만들어진다() {
        Report report = Report.builder()
                .userId(1L).discoveryRoute("지인 제보").damageType("도용").description("원본 설명")
                .sourceUrls(List.of("https://a")).evidenceIds(List.of()).targetAgencyType("경찰청").build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportSummarizer.summarize("수정된 설명", "도용")).thenReturn(Optional.of("새 요약"));

        UpdateReportRequest request = new UpdateReportRequest(null, null, null, Optional.of("수정된 설명"), null, null, null, null);
        Report updated = reportService.updateReport(1L, 1L, request);

        assertThat(updated.getDescription()).isEqualTo("수정된 설명");
        assertThat(updated.getDiscoveryRoute()).isEqualTo("지인 제보");
        assertThat(updated.getDamageType()).isEqualTo("도용");
        assertThat(updated.getSummary()).isEqualTo("새 요약");
        assertThat(updated.getTimeline()).extracting(entry -> entry.getEvent())
                .containsExactly(TimelineEvent.CREATED, TimelineEvent.UPDATED);
    }

    @Test
    void 피해_유형만_바뀌어도_요약이_다시_만들어진다() {
        Report report = Report.builder()
                .userId(1L).damageType("도용").description("원본 설명")
                .sourceUrls(List.of("https://a")).evidenceIds(List.of()).targetAgencyType("경찰청").build();
        report.updateSummary("옛 요약");
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportSummarizer.summarize("원본 설명", "얼굴 합성")).thenReturn(Optional.of("새 요약"));

        UpdateReportRequest request = new UpdateReportRequest(null, null, Optional.of("얼굴 합성"), null, null, null, null, null);

        assertThat(reportService.updateReport(1L, 1L, request).getSummary()).isEqualTo("새 요약");
    }

    @Test
    void 빈_Optional로_보낸_필드는_값이_지워진다() {
        Report report = Report.builder()
                .userId(1L).discoveryRoute("인스타그램").damageType("도용").description("원본 설명")
                .sourceUrls(List.of("https://a")).evidenceIds(List.of()).targetAgencyType("경찰청")
                .additionalNotes("메모").build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateReportRequest request = new UpdateReportRequest(
                null, Optional.empty(), null, null, Optional.empty(), null, null, Optional.empty());
        Report updated = reportService.updateReport(1L, 1L, request);

        assertThat(updated.getDiscoveryRoute()).isNull();
        assertThat(updated.getSourceUrls()).isEmpty();
        assertThat(updated.getAdditionalNotes()).isNull();
        assertThat(updated.getDamageType()).isEqualTo("도용");
        assertThat(updated.getTargetAgencyType()).isEqualTo("경찰청");
        assertThat(updated.getDescription()).isEqualTo("원본 설명");
    }

    @Test
    void 설명을_지우려고_하면_REQUIRED_FIELD_MISSING_예외가_발생한다() {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.updateReport(1L, 1L, new UpdateReportRequest(
                        null, null, null, Optional.empty(), null, null, null, null)))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REQUIRED_FIELD_MISSING);
    }

    @Test
    void 확정된_신고서는_수정할_수_없다() {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        report.markFinalized();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.updateReport(1L, 1L, new UpdateReportRequest(
                        null, null, null, Optional.of("수정 시도"), null, null, null, null)))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_ALREADY_FINALIZED);
    }

    @Test
    void 신고서를_확정하면_FINALIZED_상태로_전환된다() {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        Report finalized = reportService.finalizeReport(1L, 1L);

        assertThat(finalized.getStatus()).isEqualTo(ReportStatus.FINALIZED);
        assertThat(finalized.getTimeline()).extracting(entry -> entry.getEvent())
                .containsExactly(TimelineEvent.CREATED, TimelineEvent.FINALIZED);
    }

    @Test
    void 이미_확정된_신고서는_다시_확정할_수_없다() {
        Report report = Report.builder()
                .userId(1L).description("설명").sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        report.markFinalized();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.finalizeReport(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_ALREADY_FINALIZED);
    }

    @Test
    void 신고서를_삭제하면_이_신고서에만_쓴_증거_파일도_함께_지운다() {
        Report target = Report.builder().userId(1L).description("설명").sourceUrls(List.of())
                .evidenceIds(List.of(10L, 11L)).build();
        Report other = Report.builder().userId(1L).description("다른 신고서").sourceUrls(List.of())
                .evidenceIds(List.of(11L)).build();
        org.springframework.test.util.ReflectionTestUtils.setField(target, "id", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(other, "id", 2L);
        ReportEvidence onlyTarget = ReportEvidence.builder().userId(1L).fileUrl("/uploads/evidences/10.png").build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(target));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(target, other));
        // 11번 증거는 다른 신고서도 쓰므로 조회 대상에서 빠져야 한다.
        when(reportEvidenceRepository.findAllById(java.util.Set.of(10L))).thenReturn(List.of(onlyTarget));

        reportService.deleteReport(1L, 1L);

        verify(reportRepository).delete(target);
        verify(reportEvidenceRepository).deleteAll(List.of(onlyTarget));
        verify(fileStorage).delete("/uploads/evidences/10.png");
    }

    @Test
    void 본인_신고서는_PDF_문서를_만들어_준다() {
        Report report = Report.builder().userId(1L).description("설명").sourceUrls(List.of("https://a"))
                .evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(reportPdfRenderer.render(report)).thenReturn(new byte[]{1, 2, 3});

        assertThat(reportService.renderDocument(1L, 1L)).containsExactly(1, 2, 3);
    }

    @Test
    void 다른_사람의_신고서는_PDF_문서를_만들지_않는다() {
        Report report = Report.builder().userId(2L).description("설명").sourceUrls(List.of("https://a"))
                .evidenceIds(List.of()).build();
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.renderDocument(1L, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(reportPdfRenderer, never()).render(any());
    }

    @Test
    void 설명이_그대로면_요약을_다시_만들지_않는다() {
        Report report = Report.builder().userId(1L).description("원본 설명").damageType("도용")
                .sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        report.updateSummary("기존 요약");
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));

        reportService.updateReport(1L, 1L, new UpdateReportRequest(
                null, null, null, Optional.of("원본 설명"), null, null, null, Optional.of("메모만 바꿈")));

        assertThat(report.getSummary()).isEqualTo("기존 요약");
        verify(reportSummarizer, never()).summarize(any(), any());
    }

    @Test
    void 설명이_바뀌었는데_요약을_못_만들면_옛_요약을_남기지_않는다() {
        Report report = Report.builder().userId(1L).description("원본 설명").damageType("도용")
                .sourceUrls(List.of("https://a")).evidenceIds(List.of()).build();
        report.updateSummary("옛 요약");
        when(reportRepository.findById(1L)).thenReturn(Optional.of(report));
        when(reportSummarizer.summarize("바뀐 설명", "도용")).thenReturn(Optional.empty());

        reportService.updateReport(1L, 1L, new UpdateReportRequest(
                null, null, null, Optional.of("바뀐 설명"), null, null, null, null));

        assertThat(report.getSummary()).isNull();
    }
}
