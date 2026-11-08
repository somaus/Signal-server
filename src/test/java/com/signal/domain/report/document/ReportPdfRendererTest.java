package com.signal.domain.report.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.report.entity.Report;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReportPdfRendererTest {

    private final ReportPdfRenderer renderer = new ReportPdfRenderer();

    private Report.ReportBuilder base() {
        return Report.builder().userId(1L).description("SNS에 제 얼굴이 합성된 사진이 올라왔습니다.")
                .sourceUrls(List.of("https://example.com/post/1")).evidenceIds(List.of(1L, 2L));
    }

    private String text(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private int pages(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        }
    }

    @Test
    void 신고서_내용이_한글_그대로_PDF에_들어간다() throws IOException {
        Report report = base().damageType("얼굴 합성").discoveryRoute("인스타그램")
                .incidentDate(LocalDate.of(2026, 7, 1)).targetAgencyType("경찰청")
                .additionalNotes("가해자 계정 정보도 함께 전달 부탁드립니다.").build();
        report.updateSummary("신고인은 SNS에서 얼굴이 합성된 사진을 발견했다.");

        byte[] pdf = renderer.render(report);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("딥페이크 피해 신고서").contains("얼굴 합성").contains("인스타그램")
                .contains("2026년 7월 1일").contains("경찰청")
                .contains("SNS에 제 얼굴이 합성된 사진이 올라왔습니다.")
                .contains("신고인은 SNS에서 얼굴이 합성된 사진을 발견했다.")
                .contains("https://example.com/post/1")
                .contains("가해자 계정 정보도 함께 전달 부탁드립니다.")
                .contains("파일 2건");
    }

    @Test
    void 요약이_없으면_개요_항목을_넣지_않는다() throws IOException {
        String text = text(renderer.render(base().build()));

        assertThat(text).doesNotContain("사건 개요").contains("피해 내용(원문)");
    }

    @Test
    void 요약이_있으면_AI_요약이라는_안내와_함께_들어간다() throws IOException {
        Report report = base().build();
        report.updateSummary("요약입니다.");

        String text = text(renderer.render(report));

        assertThat(text).contains("사건 개요").contains("요약입니다.").contains("AI가");
    }

    @Test
    void 선택_항목이_비어_있어도_만들어진다() throws IOException {
        Report report = Report.builder().userId(1L).description("설명").sourceUrls(null).evidenceIds(null).build();

        String text = text(renderer.render(report));

        assertThat(text).contains("설명").contains("첨부한 증거 파일이 없습니다.");
    }

    @Test
    void 긴_원문은_여러_페이지로_나뉘고_글자가_빠지지_않는다() throws IOException {
        String sentence = "저는 지난달부터 합성된 사진 때문에 많이 힘들었습니다. ";
        String description = (sentence.repeat(40) + "\n\n").repeat(6) + "마지막 문장입니다.";
        Report report = base().description(description).build();

        byte[] pdf = renderer.render(report);

        assertThat(pages(pdf)).isGreaterThan(1);
        String text = text(pdf).replaceAll("\\s+", "");
        assertThat(text).contains("마지막문장입니다.");
        assertThat(text.split("합성된사진때문에", -1).length - 1).isEqualTo(240);
    }

    @Test
    void 공백_없이_이어진_긴_글과_URL도_줄바꿈된다() throws IOException {
        String url = "https://example.com/" + "a".repeat(300);
        Report report = base().description("가".repeat(500)).sourceUrls(List.of(url)).build();

        String text = text(renderer.render(report)).replaceAll("\\s+", "");

        assertThat(text).contains("가".repeat(500)).contains(url);
    }

    @Test
    void 글꼴에_없는_글자와_제어_문자가_있어도_실패하지_않는다() throws IOException {
        Report report = base().description("이모지 😀 와 일본어 ひらがな, 제어\u0007문자, 탭\t포함").build();

        String text = text(renderer.render(report));

        assertThat(text).contains("이모지").contains("제어문자").contains("탭 포함");
    }

    @Test
    void 확정된_신고서와_초안은_상태가_다르게_표시된다() throws IOException {
        Report draft = base().build();
        Report finalized = base().build();
        finalized.markFinalized();

        assertThat(text(renderer.render(draft))).contains("초안");
        assertThat(text(renderer.render(finalized))).contains("작성 완료").doesNotContain("초안");
    }

    @Test
    void 저장_전이라_id가_없는_신고서도_만들어진다() throws IOException {
        Report report = base().build();
        ReflectionTestUtils.setField(report, "id", null);

        assertThat(renderer.render(report)).isNotEmpty();
    }
}
