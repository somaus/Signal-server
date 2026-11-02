package com.signal.domain.monitoring.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SearchImagePreprocessorTest {

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void 긴_변이_1600px을_넘으면_비율을_지켜_줄인다() throws Exception {
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(SearchImagePreprocessor.shrink(png(4000, 2000))));

        assertThat(result.getWidth()).isEqualTo(1600);
        assertThat(result.getHeight()).isEqualTo(800);
    }

    @Test
    void 작은_이미지는_크기를_유지하고_알파_채널_PNG도_JPEG로_바꾼다() throws Exception {
        byte[] jpeg = SearchImagePreprocessor.shrink(png(300, 200));
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(jpeg));

        assertThat(result.getWidth()).isEqualTo(300);
        assertThat(result.getHeight()).isEqualTo(200);
        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);   // JPEG SOI
    }

    @Test
    void 이미지가_아니면_다시_시도할_수_없는_실패다() {
        assertThatThrownBy(() -> SearchImagePreprocessor.shrink(new byte[]{1, 2, 3}))
                .isInstanceOf(NonRetryableSearchException.class);
    }
}
