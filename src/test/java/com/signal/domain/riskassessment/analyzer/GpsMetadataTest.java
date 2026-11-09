package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class GpsMetadataTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = GpsMetadataTest.class.getResourceAsStream("/fixtures/exif/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void GPS_좌표가_있으면_참이다() throws IOException {
        assertThat(GpsMetadata.present(fixture("gps.jpg"))).isTrue();
    }

    @Test
    void 좌표가_0_0이면_위치가_없는_것으로_본다() throws IOException {
        assertThat(GpsMetadata.present(fixture("gps-zero.jpg"))).isFalse();
    }

    @Test
    void EXIF가_없으면_거짓이다() throws IOException {
        assertThat(GpsMetadata.present(fixture("no-exif.jpg"))).isFalse();
    }

    @Test
    void 이미지가_아닌_바이트는_예외_없이_거짓이다() {
        assertThat(GpsMetadata.present("not an image".getBytes())).isFalse();
        assertThat(GpsMetadata.present(new byte[0])).isFalse();
    }
}
