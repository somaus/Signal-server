package com.signal.global.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UploadFileTypeTest {

    private static byte[] ftyp(String brand) {
        byte[] head = new byte[12];
        byte[] box = ("ftyp" + brand).getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(box, 0, head, 4, box.length);
        return head;
    }

    @Test
    void 시그니처가_정확히_같은_길이여도_JPEG로_판별한다() {
        assertThat(UploadFileType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
                .contains(UploadFileType.JPEG);
    }

    @Test
    void 시그니처보다_짧으면_판별하지_않는다() {
        assertThat(UploadFileType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
    }

    @Test
    void PNG와_PDF를_판별한다() {
        assertThat(UploadFileType.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .contains(UploadFileType.PNG);
        assertThat(UploadFileType.detect("%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1)))
                .contains(UploadFileType.PDF);
    }

    @Test
    void ftyp_브랜드가_qt면_MOV_아니면_MP4다() {
        assertThat(UploadFileType.detect(ftyp("qt  "))).contains(UploadFileType.MOV);
        assertThat(UploadFileType.detect(ftyp("isom"))).contains(UploadFileType.MP4);
    }

    @Test
    void ftyp가_아니거나_너무_짧으면_판별하지_않는다() {
        byte[] notFtyp = new byte[12];
        System.arraycopy("moov".getBytes(StandardCharsets.ISO_8859_1), 0, notFtyp, 4, 4);

        assertThat(UploadFileType.detect(notFtyp)).isEmpty();
        assertThat(UploadFileType.detect(new byte[11])).isEmpty();
    }
}
