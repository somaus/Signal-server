package com.signal.domain.deepfakedetection.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.file.FileStorage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StubDeepfakeDetectorTest {

    @Mock
    private FileStorage fileStorage;

    @Mock
    private DeepfakeDetectionRepository deepfakeDetectionRepository;

    private StubDeepfakeDetector stubDeepfakeDetector;

    @BeforeEach
    void setUp() {
        stubDeepfakeDetector = new StubDeepfakeDetector(fileStorage, deepfakeDetectionRepository, 0);
    }

    private DeepfakeDetection sampleDetection() {
        return DeepfakeDetection.builder()
                .userId(1L)
                .fileUrl("/uploads/deepfake-detections/original.png")
                .build();
    }

    @Test
    void 처리에_성공하면_verdict와_근거와_하이라이트결과를_반영하고_저장한다() {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load("/uploads/deepfake-detections/original.png")).thenReturn(new byte[]{1, 2, 3});
        when(fileStorage.store(any(byte[].class), anyString())).thenReturn("/uploads/deepfake-detections/highlighted.png");

        stubDeepfakeDetector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(detection.getVerdict()).isNotNull();
        assertThat(detection.getConfidence()).isBetween(0.5, 1.0);
        assertThat(detection.getRiskScore()).isBetween(0, 100);
        assertThat(detection.getEvidences()).isNotEmpty();
        assertThat(detection.getHighlightedResultUrl()).isEqualTo("/uploads/deepfake-detections/highlighted.png");
        assertThat(detection.getFallbackUsed()).isTrue();
        org.mockito.Mockito.verify(deepfakeDetectionRepository).save(detection);
    }

    @Test
    void 영상_입력이면_근거에_프레임_번호가_채워진다() {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(fileStorage.store(any(byte[].class), anyString())).thenReturn("/uploads/deepfake-detections/highlighted.png");

        stubDeepfakeDetector.detect(1L, "/uploads/deepfake-detections/original.mp4", true);

        assertThat(detection.getEvidences()).allSatisfy(evidence -> assertThat(evidence.getFrame()).isNotNull());
    }

    @Test
    void 이미지_입력이면_근거에_프레임_번호가_없다() {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(fileStorage.store(any(byte[].class), anyString())).thenReturn("/uploads/deepfake-detections/highlighted.png");

        stubDeepfakeDetector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getEvidences()).allSatisfy(evidence -> assertThat(evidence.getFrame()).isNull());
    }

    @Test
    void 원본파일_로드에_실패하면_FAILED로_저장한다() {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenThrow(new RuntimeException("로드 실패"));

        stubDeepfakeDetector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        org.mockito.Mockito.verify(deepfakeDetectionRepository).save(detection);
    }

    @Test
    void 동일한_파일은_항상_동일한_결과를_생성한다() {
        DeepfakeDetection first = sampleDetection();
        DeepfakeDetection second = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(first));
        when(deepfakeDetectionRepository.findById(2L)).thenReturn(Optional.of(second));
        when(fileStorage.load(anyString())).thenReturn(new byte[]{9, 9, 9});
        when(fileStorage.store(any(byte[].class), anyString())).thenReturn("/uploads/deepfake-detections/highlighted.png");

        stubDeepfakeDetector.detect(1L, "/uploads/deepfake-detections/same.png", false);
        stubDeepfakeDetector.detect(2L, "/uploads/deepfake-detections/same.png", false);

        assertThat(first.getVerdict()).isEqualTo(second.getVerdict());
        assertThat(first.getRiskScore()).isEqualTo(second.getRiskScore());
        assertThat(first.getConfidence()).isEqualTo(second.getConfidence());
        assertThat(first.getEvidences()).hasSameSizeAs(second.getEvidences());
    }
}
