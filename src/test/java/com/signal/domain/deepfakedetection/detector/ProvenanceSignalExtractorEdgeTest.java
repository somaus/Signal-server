package com.signal.domain.deepfakedetection.detector;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.deepfakedetection.entity.ProvenanceSignal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProvenanceSignalExtractorEdgeTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final String AI_MARK = "trainedAlgorithmicMedia";

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        int length = data.length;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(data);
        out.writeBytes(new byte[] {0, 0, 0, 0});
    }

    private static byte[] text(String keyword, String value) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(keyword.getBytes(StandardCharsets.ISO_8859_1));
        data.write(0);
        data.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
        return data.toByteArray();
    }

    private static byte[] international(String keyword, int compressionFlag, String value) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(keyword.getBytes(StandardCharsets.ISO_8859_1));
        data.write(0);
        data.write(compressionFlag);
        data.write(0);
        data.write(0);
        data.write(0);
        data.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        return data.toByteArray();
    }

    private static byte[] png(Object... typeAndData) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);
        chunk(out, "IHDR", new byte[13]);
        for (int i = 0; i < typeAndData.length; i += 2) {
            chunk(out, (String) typeAndData[i], (byte[]) typeAndData[i + 1]);
        }
        return out.toByteArray();
    }

    private static byte[] withMarkAt(int totalSize, int markStart) {
        byte[] file = new byte[totalSize];
        Arrays.fill(file, (byte) 'x');
        byte[] mark = AI_MARK.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(mark, 0, file, markStart, mark.length);
        return file;
    }

    @Test
    void 앞부분_검색_한도_안의_표시는_찾고_밖의_표시는_찾지_않는다() {
        int limit = ProvenanceSignalExtractor.SCAN_LIMIT;
        byte[] mark = AI_MARK.getBytes(StandardCharsets.ISO_8859_1);

        assertThat(ProvenanceSignalExtractor.extract(withMarkAt(limit, limit - mark.length)))
                .containsExactly(ProvenanceSignal.AI_SOURCE_DECLARED);
        assertThat(ProvenanceSignalExtractor.extract(withMarkAt(limit + 1000, limit - mark.length)))
                .containsExactly(ProvenanceSignal.AI_SOURCE_DECLARED);
        assertThat(ProvenanceSignalExtractor.extract(withMarkAt(limit + 1000, limit + 10))).isEmpty();
    }

    @Test
    void C2PA는_jumb와_c2pa가_둘_다_있어야_알린다() {
        assertThat(ProvenanceSignalExtractor.extract("padding padding jumb only".getBytes(StandardCharsets.ISO_8859_1)))
                .isEmpty();
        assertThat(ProvenanceSignalExtractor.extract("padding padding c2pa only".getBytes(StandardCharsets.ISO_8859_1)))
                .isEmpty();
    }

    @Test
    void PNG_시그니처가_한_바이트라도_다르면_청크를_읽지_않는다() {
        byte[] valid = png("tEXt", text("parameters", "Steps: 20"));
        assertThat(ProvenanceSignalExtractor.extract(valid)).containsExactly(ProvenanceSignal.GENERATOR_METADATA);

        for (int index : new int[] {0, 3, 7}) {
            byte[] broken = valid.clone();
            broken[index] ^= 0x01;
            assertThat(ProvenanceSignalExtractor.extract(broken)).as("byte %d", index).isEmpty();
        }
    }

    @Test
    void IEND_뒤의_청크는_읽지_않는다() {
        byte[] file = png("IEND", new byte[0], "tEXt", text("parameters", "Steps: 20"));

        assertThat(ProvenanceSignalExtractor.extract(file)).isEmpty();
    }

    @Test
    void 길이가_파일_끝을_넘는_청크에서는_멈추고_앞의_결과만_남긴다() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);
        chunk(out, "IHDR", new byte[13]);
        chunk(out, "tEXt", text("parameters", "Steps: 20"));
        out.writeBytes(new byte[] {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 't', 'E', 'X', 't', 0, 0, 0, 0, 0, 0});

        assertThat(ProvenanceSignalExtractor.extract(out.toByteArray()))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
    }

    @Test
    void 긴_청크의_길이를_바르게_읽어_그_뒤_청크도_찾는다() {
        for (int size : new int[] {300, 70_000}) {
            byte[] filler = new byte[size];
            Arrays.fill(filler, (byte) 'a');
            byte[] file = png("tEXt", text("Comment", new String(filler, StandardCharsets.ISO_8859_1)),
                    "tEXt", text("parameters", "Steps: 20"));

            assertThat(ProvenanceSignalExtractor.extract(file)).as("size %d", size)
                    .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        }
    }

    @Test
    void 텍스트_값은_앞_8192바이트_안에서만_본다() {
        String near = "a".repeat(8000) + "Steps: 20";
        String far = "a".repeat(8200) + "Steps: 20";

        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("parameters", near))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("parameters", far)))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", international("parameters", 0, near))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", international("parameters", 0, far)))).isEmpty();
    }

    @Test
    void 키워드별_판정_규칙() {
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("invokeai_metadata", "x"))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("sd-metadata", "x"))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("parameters", "Sampler: Euler"))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("workflow", "{\"nodes\": []}"))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("workflow", "{\"links\": []}")))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", text("Comment", "class_type Steps: Sampler:"))))
                .isEmpty();
    }

    @Test
    void 압축된_텍스트는_키워드만_보고_본문은_읽지_않는다() {
        assertThat(ProvenanceSignalExtractor.extract(png("zTXt", text("invokeai", "compressed"))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
        assertThat(ProvenanceSignalExtractor.extract(png("zTXt", text("parameters", "Steps: 20")))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", international("parameters", 1, "Steps: 20")))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", international("invokeai", 1, "x"))))
                .containsExactly(ProvenanceSignal.GENERATOR_METADATA);
    }

    @Test
    void 키워드_끝이_없거나_구조가_깨진_텍스트_청크는_무시한다() {
        byte[] noNul = "parametersSteps: 20".getBytes(StandardCharsets.ISO_8859_1);
        byte[] shortInternational = "parameters\0\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[] noLanguageEnd = "parameters\0\0\0abc".getBytes(StandardCharsets.ISO_8859_1);
        byte[] noTranslatedEnd = "parameters\0\0\0\0abc".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(ProvenanceSignalExtractor.extract(png("tEXt", noNul))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("zTXt", noNul))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", noNul))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", shortInternational))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", noLanguageEnd))).isEmpty();
        assertThat(ProvenanceSignalExtractor.extract(png("iTXt", noTranslatedEnd))).isEmpty();
    }
}
