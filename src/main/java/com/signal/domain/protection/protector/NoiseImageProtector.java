package com.signal.domain.protection.protector;

import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.global.file.FileStorage;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 서버에서 직접 이미지에 픽셀 노이즈를 삽입해 보호 이미지를 만드는 구현체.
 * 2x2 블록 단위로 상관된 노이즈를 RGB 채널에 더해, 단순 재업로드·재압축·축소 후에도 일부가 남도록 한다.
 * 알파 채널은 건드리지 않으며 결과는 무손실 PNG로 저장한다. PNG로 재인코딩하면 EXIF가 사라지므로(GPS 등
 * 메타데이터 제거 효과) EXIF 회전 방향은 픽셀에 미리 반영해 사진이 돌아가 보이지 않게 한다.
 *
 * 주의: 이는 학습된 모델을 겨냥해 최적화한 적대적 섭동(adversarial perturbation)이 아니라 일반 노이즈
 * 삽입이다. 도용·합성 방지를 보장하지 않으며, 모델 기반 적대적 보호는 별도 엔진 연동이 필요하다.
 *
 * protection.mode=http 이면 이 구현체 대신 {@link HttpImageProtector}가 사용된다.
 *
 * ProtectionService(→ImageProtector) 의존 방향의 순환을 피하기 위해 완료/실패 처리 시
 * ProtectionService가 아닌 ProtectionRepository에 직접 접근한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "protection.mode", havingValue = "noise", matchIfMissing = true)
public class NoiseImageProtector implements ImageProtector {

    private static final String PROTECTED_IMAGE_DIRECTORY = "protections";
    private static final int BLOCK_SIZE = 2;
    private static final long MAX_PIXELS = 50_000_000L;

    private final FileStorage fileStorage;
    private final ProtectionRepository protectionRepository;
    private final SecureRandom random = new SecureRandom();

    public NoiseImageProtector(FileStorage fileStorage, ProtectionRepository protectionRepository) {
        this.fileStorage = fileStorage;
        this.protectionRepository = protectionRepository;
    }

    @Override
    @Async
    public void protect(Long protectionId, String originalImageUrl, ProtectionLevel protectionLevel) {
        try {
            byte[] originalBytes = fileStorage.load(originalImageUrl);
            BufferedImage original = ImageOrientation.apply(readImage(originalBytes), readOrientation(originalBytes));

            ProtectedImage result = addNoise(original, amplitude(protectionLevel));
            String protectedImageUrl = fileStorage.store(result.png(), PROTECTED_IMAGE_DIRECTORY);

            markCompleted(protectionId, protectedImageUrl, result.visualDifference());
        } catch (Exception e) {
            log.error("이미지 보호 처리 실패: protectionId={}", protectionId, e);
            markFailed(protectionId);
        }
    }

    private BufferedImage readImage(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("이미지로 해석할 수 없는 파일입니다.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new IOException("보호 처리 가능한 해상도를 초과했습니다: " + pixels + "px");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private int readOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // EXIF가 없거나 판독 불가하면 회전 없음으로 간주한다.
        }
        return 1;
    }

    ProtectedImage addNoise(BufferedImage original, int amplitude) throws IOException {
        int width = original.getWidth();
        int height = original.getHeight();
        BufferedImage protectedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int blocksPerRow = (width + BLOCK_SIZE - 1) / BLOCK_SIZE;
        int blockRows = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
        int[][] noise = new int[blocksPerRow * blockRows][3];
        for (int[] block : noise) {
            for (int c = 0; c < 3; c++) {
                block[c] = random.nextInt(2 * amplitude + 1) - amplitude;
            }
        }

        double squaredError = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = original.getRGB(x, y);
                int[] delta = noise[(y / BLOCK_SIZE) * blocksPerRow + (x / BLOCK_SIZE)];
                int alpha = (argb >>> 24) & 0xFF;
                int out = alpha << 24;
                for (int c = 0; c < 3; c++) {
                    int shift = 16 - 8 * c;
                    int value = (argb >> shift) & 0xFF;
                    int noisy = Math.max(0, Math.min(255, value + delta[c]));
                    squaredError += (double) (noisy - value) * (noisy - value);
                    out |= noisy << shift;
                }
                protectedImage.setRGB(x, y, out);
            }
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(protectedImage, "png", buffer);

        // 0~1로 정규화한 RMS 픽셀 차이 (0 = 동일)
        double rms = Math.sqrt(squaredError / ((double) width * height * 3)) / 255.0;
        return new ProtectedImage(buffer.toByteArray(), Math.round(rms * 10000) / 10000.0);
    }

    static int amplitude(ProtectionLevel protectionLevel) {
        return switch (protectionLevel) {
            case LIGHT -> 3;
            case NORMAL -> 6;
            case STRONG -> 12;
        };
    }

    record ProtectedImage(byte[] png, double visualDifference) {
    }

    private void markCompleted(Long protectionId, String protectedImageUrl, double visualDifference) {
        protectionRepository.findById(protectionId).ifPresent(protection -> {
            protection.complete(protectedImageUrl, visualDifference);
            protectionRepository.save(protection);
        });
    }

    private void markFailed(Long protectionId) {
        protectionRepository.findById(protectionId).ifPresent(protection -> {
            protection.fail();
            protectionRepository.save(protection);
        });
    }
}
