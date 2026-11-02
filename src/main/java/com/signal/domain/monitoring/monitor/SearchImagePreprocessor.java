package com.signal.domain.monitoring.monitor;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 검색 API에 보내기 전에 이미지를 줄여 JPEG로 다시 인코딩한다. 업로드는 10MB까지 받지만 base64는 1.33배가 되어
 * Vision의 요청 한도(10MB)를 넘을 수 있고, 역검색에는 긴 변 1600px이면 충분하다.
 */
public final class SearchImagePreprocessor {

    static final int MAX_EDGE_PX = 1600;

    private SearchImagePreprocessor() {
    }

    public static byte[] shrink(byte[] image) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
            if (source == null) {
                throw new NonRetryableSearchException("읽을 수 없는 이미지 형식입니다.");
            }
            int longEdge = Math.max(source.getWidth(), source.getHeight());
            double scale = longEdge > MAX_EDGE_PX ? (double) MAX_EDGE_PX / longEdge : 1.0;
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

            // JPEG는 알파 채널이 없어 흰 배경 위에 그린다.
            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.drawImage(source, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(target, "jpg", out)) {
                throw new NonRetryableSearchException("이미지를 JPEG로 변환하지 못했습니다.");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new NonRetryableSearchException("이미지를 읽지 못했습니다.", e);
        }
    }
}
