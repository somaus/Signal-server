package com.signal.domain.report.document;

import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportStatus;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 신고서 내용으로 신고 문서(PDF)를 만든다. 한글 표시를 위해 나눔고딕(SIL OFL 1.1)을 PDF에 포함한다.
 * 글꼴 파일은 시작할 때 한 번만 읽어 파싱해 두고(요청마다 파싱하지 않는다), 문서마다 쓰이는 글자만 골라 담는다.
 * 파싱한 글꼴은 여러 요청이 함께 쓰기에 안전하지 않으므로 문서를 만드는 동안은 잠금을 잡는다(문서 한 장에 수십 ms).
 *
 * 문서는 신고서 내용을 정리한 신고 보조 자료다. 접수 기관 고유의 양식이 아니며, 요약은 AI가 만들어
 * 원문과 다를 수 있으므로 원문(피해 내용)을 그대로 함께 싣는다.
 */
@Slf4j
@Component
public class ReportPdfRenderer {

    private static final String REGULAR_FONT = "fonts/NanumGothic-Regular.ttf";
    private static final String BOLD_FONT = "fonts/NanumGothic-Bold.ttf";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일");

    private static final float MARGIN = 56f;
    private static final float BODY_SIZE = 10.5f;
    private static final float LEADING = 17f;
    private static final float LABEL_WIDTH = 96f;

    private final TrueTypeFont regularFont;
    private final TrueTypeFont boldFont;
    private final ReentrantLock renderLock = new ReentrantLock();

    public ReportPdfRenderer() {
        this.regularFont = parseFont(REGULAR_FONT);
        this.boldFont = parseFont(BOLD_FONT);
    }

    public byte[] render(Report report) {
        renderLock.lock();
        try (PDDocument document = new PDDocument()) {
            PDFont regular = PDType0Font.load(document, regularFont, true);
            PDFont bold = PDType0Font.load(document, boldFont, true);
            Page page = new Page(document, regular, bold);

            writeHeader(page, report);
            writeBasicInfo(page, report);
            if (StringUtils.hasText(report.getSummary())) {
                page.heading("사건 개요");
                page.paragraph(report.getSummary(), regular, BODY_SIZE, 0);
                page.paragraph("※ 위 개요는 AI가 아래 '피해 내용(원문)'을 요약한 것으로 원문과 다를 수 있습니다. 내용이 다르면 원문이 우선합니다.",
                        regular, 9f, 0);
                page.space(8);
            }
            page.heading("피해 내용(원문)");
            page.paragraph(orDash(report.getDescription()), regular, BODY_SIZE, 0);
            page.space(8);
            writeSources(page, report);
            if (StringUtils.hasText(report.getAdditionalNotes())) {
                page.heading("추가 전달 사항");
                page.paragraph(report.getAdditionalNotes(), regular, BODY_SIZE, 0);
                page.space(8);
            }
            writeEvidence(page, report);
            page.footerNote("이 문서는 Signal에서 신고서 작성을 돕기 위해 입력하신 내용을 정리한 자료이며, 특정 기관의 접수 양식이 아닙니다. "
                    + "제출하실 기관의 양식과 안내를 함께 확인해 주세요.");
            page.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("신고 문서 PDF 생성 실패: reportId={}", report.getId(), e);
            throw new SignalException(ErrorCode.INTERNAL_SERVER_ERROR);
        } finally {
            renderLock.unlock();
        }
    }

    private void writeHeader(Page page, Report report) throws IOException {
        page.title("딥페이크 피해 신고서");
        String state = report.getStatus() == ReportStatus.FINALIZED ? "작성 완료" : "초안(아직 작성 완료 전)";
        page.paragraph("상태: " + state + "    작성일: " + report.getCreatedAt().toLocalDate().format(DATE),
                page.regular, 9.5f, 0);
        page.space(10);
    }

    private void writeBasicInfo(Page page, Report report) throws IOException {
        page.heading("기본 정보");
        page.row("피해 유형", orDash(report.getDamageType()));
        page.row("발생(인지)일", report.getIncidentDate() != null ? formatDate(report.getIncidentDate()) : "-");
        page.row("발견 경로", orDash(report.getDiscoveryRoute()));
        page.row("제출 대상", orDash(report.getTargetAgencyType()));
        page.space(8);
    }

    private void writeSources(Page page, Report report) throws IOException {
        page.heading("게시물·출처 주소");
        List<String> urls = report.getSourceUrls() == null ? List.of() : report.getSourceUrls();
        if (urls.isEmpty()) {
            page.paragraph("-", page.regular, BODY_SIZE, 0);
        }
        for (int i = 0; i < urls.size(); i++) {
            page.numbered((i + 1) + ".", urls.get(i));
        }
        page.space(8);
    }

    private void writeEvidence(Page page, Report report) throws IOException {
        int count = report.getEvidenceIds() == null ? 0 : report.getEvidenceIds().size();
        page.heading("첨부 증거");
        page.paragraph(count == 0
                        ? "첨부한 증거 파일이 없습니다."
                        : "Signal에 업로드한 증거 파일 " + count + "건이 있습니다. 이 문서에는 파일이 포함되어 있지 않으므로 제출할 때 함께 전달해 주세요.",
                page.regular, BODY_SIZE, 0);
        page.space(8);
    }

    private static String formatDate(LocalDate date) {
        return date.format(DATE);
    }

    private static String orDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private static TrueTypeFont parseFont(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new TTFParser().parse(new ByteArrayInputStream(in.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("PDF 글꼴을 읽을 수 없습니다: " + path, e);
        }
    }

    /** 페이지 나눔과 줄바꿈을 처리하며 글을 쌓아 가는 도우미. */
    private static final class Page {

        private final PDDocument document;
        private final PDFont regular;
        private final PDFont bold;
        private final Map<PDFont, Map<Integer, Boolean>> glyphSupport = new HashMap<>();
        private final float contentWidth = PDRectangle.A4.getWidth() - 2 * MARGIN;
        private PDPageContentStream stream;
        private float y;

        Page(PDDocument document, PDFont regular, PDFont bold) throws IOException {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            newPage();
        }

        void title(String text) throws IOException {
            paragraph(text, bold, 20f, 0, 28f);
        }

        void heading(String text) throws IOException {
            ensureSpace(LEADING * 4);   // 제목만 페이지 아래에 남지 않게, 뒤따르는 글 두 줄 정도가 들어갈 자리를 함께 확인한다
            paragraph(text, bold, 12.5f, 0, 20f);
            line();
        }

        void row(String label, String value) throws IOException {
            List<String> valueLines = wrap(value, regular, BODY_SIZE, contentWidth - LABEL_WIDTH);
            ensureSpace(LEADING);
            float rowTop = y;
            writeLine(label, bold, BODY_SIZE, MARGIN, rowTop);
            for (String line : valueLines) {
                ensureSpace(LEADING);
                writeLine(line, regular, BODY_SIZE, MARGIN + LABEL_WIDTH, y);
                y -= LEADING;
            }
        }

        /** 번호를 왼쪽에 두고 내용은 들여쓴 채 줄바꿈한다. 긴 주소가 나뉘어도 번호가 홀로 한 줄에 남지 않는다. */
        void numbered(String number, String text) throws IOException {
            float indent = 22f;
            List<String> lines = wrap(text, regular, BODY_SIZE, contentWidth - indent);
            ensureSpace(LEADING);
            writeLine(number, regular, BODY_SIZE, MARGIN, y);
            for (String line : lines) {
                ensureSpace(LEADING);
                writeLine(line, regular, BODY_SIZE, MARGIN + indent, y);
                y -= LEADING;
            }
        }

        void paragraph(String text, PDFont font, float size, float indent) throws IOException {
            paragraph(text, font, size, indent, LEADING);
        }

        private void paragraph(String text, PDFont font, float size, float indent, float leading) throws IOException {
            for (String line : wrap(text, font, size, contentWidth - indent)) {
                ensureSpace(leading);
                writeLine(line, font, size, MARGIN + indent, y);
                y -= leading;
            }
        }

        void footerNote(String text) throws IOException {
            space(6);
            line();
            paragraph(text, regular, 8.5f, 0, 14f);
        }

        void space(float height) {
            y -= height;
        }

        void close() throws IOException {
            stream.close();
        }

        private void line() throws IOException {
            ensureSpace(8);
            stream.setLineWidth(0.6f);
            stream.moveTo(MARGIN, y + 6);
            stream.lineTo(MARGIN + contentWidth, y + 6);
            stream.stroke();
            y -= 6;
        }

        private void writeLine(String text, PDFont font, float size, float x, float baseline) throws IOException {
            if (text.isEmpty()) {
                return;
            }
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline - size);
            stream.showText(text);
            stream.endText();
        }

        private void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) {
                stream.close();
                newPage();
            }
        }

        private void newPage() throws IOException {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        /** 글꼴에 없는 글자(이모지 등)는 '?'로 바꾸고, 줄 길이에 맞춰 나눈다. 빈 줄은 유지한다. */
        List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String paragraph : sanitize(text, font).split("\n", -1)) {
                StringBuilder current = new StringBuilder();
                float currentWidth = 0;
                int lastBreak = -1;
                for (int i = 0; i < paragraph.length(); ) {
                    int cp = paragraph.codePointAt(i);
                    String ch = new String(Character.toChars(cp));
                    float w = font.getStringWidth(ch) / 1000f * size;
                    if (currentWidth + w > maxWidth && current.length() > 0) {
                        if (lastBreak > 0 && cp != ' ') {
                            lines.add(current.substring(0, lastBreak).stripTrailing());
                            String rest = current.substring(lastBreak);
                            current = new StringBuilder(rest.stripLeading());
                            currentWidth = font.getStringWidth(current.toString()) / 1000f * size;
                        } else {
                            lines.add(current.toString().stripTrailing());
                            current = new StringBuilder();
                            currentWidth = 0;
                        }
                        lastBreak = -1;
                        if (cp == ' ') {
                            i += Character.charCount(cp);
                            continue;
                        }
                        // 단어 단위로 넘겨 받은 앞부분에 이 글자까지 이으면 또 넘칠 수 있다. 그때는 여기서 줄을 닫는다.
                        if (currentWidth + w > maxWidth && current.length() > 0) {
                            lines.add(current.toString().stripTrailing());
                            current = new StringBuilder();
                            currentWidth = 0;
                        }
                    }
                    if (cp == ' ') {
                        lastBreak = current.length() + 1;
                    }
                    current.append(ch);
                    currentWidth += w;
                    i += Character.charCount(cp);
                }
                lines.add(current.toString().stripTrailing());
            }
            return lines;
        }

        private String sanitize(String text, PDFont font) {
            StringBuilder sb = new StringBuilder();
            text.replace("\r\n", "\n").replace('\r', '\n').codePoints().forEach(cp -> {
                if (cp == '\n') {
                    sb.append('\n');
                } else if (cp == '\t') {
                    sb.append(' ');
                } else if (Character.isISOControl(cp) || Character.isSurrogate((char) cp) && cp < 0x10000) {
                    // 제어 문자는 버린다.
                } else {
                    sb.appendCodePoint(supports(font, cp) ? cp : '?');
                }
            });
            return sb.toString();
        }

        private boolean supports(PDFont font, int codePoint) {
            return glyphSupport.computeIfAbsent(font, f -> new HashMap<>()).computeIfAbsent(codePoint, cp -> {
                try {
                    font.getStringWidth(new String(Character.toChars(cp)));
                    return true;
                } catch (IllegalArgumentException | IOException e) {
                    return false;
                }
            });
        }
    }
}
