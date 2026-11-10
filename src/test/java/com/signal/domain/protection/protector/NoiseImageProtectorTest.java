package com.signal.domain.protection.protector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.entity.ProtectionStatus;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NoiseImageProtectorTest {

    @Mock
    private FileStorage fileStorage;

    @Mock
    private ProtectionRepository protectionRepository;

    private NoiseImageProtector protector;

    @BeforeEach
    void setUp() {
        protector = new NoiseImageProtector(fileStorage, protectionRepository);
    }

    private Protection sampleProtection() {
        return Protection.builder()
                .userId(1L)
                .assessmentId(10L)
                .protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl("/uploads/risk-assessments/original.png")
                .build();
    }

    private byte[] solidPng(int rgb) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void 처리에_성공하면_실제로_변형된_이미지를_저장하고_측정한_시각적_차이를_반영한다() throws Exception {
        Protection protection = sampleProtection();
        byte[] original = solidPng(0x808080);
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load("/uploads/risk-assessments/original.png")).thenReturn(original);
        when(fileStorage.store(any(byte[].class), eq("protections")))
                .thenReturn("/uploads/protections/generated.png");

        protector.protect(1L, "/uploads/risk-assessments/original.png", ProtectionLevel.NORMAL);

        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.COMPLETED);
        assertThat(protection.getProtectedImageUrl()).isEqualTo("/uploads/protections/generated.png");
        assertThat(protection.getVisualDifference()).isPositive().isLessThan(0.1);

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorage).store(stored.capture(), anyString());
        assertThat(stored.getValue()).isNotEqualTo(original);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(stored.getValue()));
        assertThat(result.getWidth()).isEqualTo(32);
        int maxDelta = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int rgb = result.getRGB(x, y);
                for (int shift : new int[]{16, 8, 0}) {
                    maxDelta = Math.max(maxDelta, Math.abs(((rgb >> shift) & 0xFF) - 0x80));
                }
            }
        }
        assertThat(maxDelta).isPositive().isLessThanOrEqualTo(NoiseImageProtector.amplitude(ProtectionLevel.NORMAL));
    }

    @Test
    void 보호_수준이_높을수록_노이즈_진폭이_커진다() {
        assertThat(NoiseImageProtector.amplitude(ProtectionLevel.LIGHT))
                .isLessThan(NoiseImageProtector.amplitude(ProtectionLevel.NORMAL));
        assertThat(NoiseImageProtector.amplitude(ProtectionLevel.NORMAL))
                .isLessThan(NoiseImageProtector.amplitude(ProtectionLevel.STRONG));
    }

    @Test
    void 이미지로_해석할_수_없는_파일이면_FAILED로_저장한다() {
        Protection protection = sampleProtection();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load(anyString())).thenReturn(new byte[]{1, 2, 3});

        protector.protect(1L, "/uploads/risk-assessments/original.png", ProtectionLevel.NORMAL);

        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.FAILED);
        verify(protectionRepository).save(protection);
        verify(fileStorage, never()).store(any(byte[].class), anyString());
    }

    @Test
    void 원본이미지_로드에_실패하면_FAILED로_저장한다() {
        Protection protection = sampleProtection();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load(anyString())).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        protector.protect(1L, "/uploads/risk-assessments/original.png", ProtectionLevel.NORMAL);

        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.FAILED);
        verify(protectionRepository).save(protection);
    }
}
