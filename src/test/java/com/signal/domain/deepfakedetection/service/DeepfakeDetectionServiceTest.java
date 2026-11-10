package com.signal.domain.deepfakedetection.service;

import com.signal.global.file.TestFileBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.deepfakedetection.detector.DeepfakeDetector;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.file.FileStorage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class DeepfakeDetectionServiceTest {

    @Mock
    private DeepfakeDetectionRepository deepfakeDetectionRepository;

    @Mock
    private DeepfakeDetector deepfakeDetector;

    @Mock
    private FileStorage fileStorage;

    private DeepfakeDetectionService deepfakeDetectionService;

    @BeforeEach
    void setUp() {
        deepfakeDetectionService = new DeepfakeDetectionService(deepfakeDetectionRepository, deepfakeDetector, fileStorage);
    }

    @Test
    void 이미지를_업로드하면_PROCESSING_상태로_저장되고_탐지가_시작된다() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TestFileBytes.PNG);
        when(fileStorage.store(any(MultipartFile.class), anyString())).thenReturn("/uploads/deepfake-detections/generated.png");
        when(deepfakeDetectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeepfakeDetection detection = deepfakeDetectionService.createDetection(1L, null, file);

        assertThat(detection.getUserId()).isEqualTo(1L);
        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.PROCESSING);
        assertThat(detection.getFileUrl()).isEqualTo("/uploads/deepfake-detections/generated.png");
        verify(deepfakeDetector).detect(eq(detection.getId()), eq("/uploads/deepfake-detections/generated.png"), eq(false));
    }

    @Test
    void 영상을_업로드하면_isVideo_true로_탐지가_시작된다() {
        MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", TestFileBytes.MP4);
        when(fileStorage.store(any(MultipartFile.class), anyString())).thenReturn("/uploads/deepfake-detections/generated.mp4");
        when(deepfakeDetectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeepfakeDetection detection = deepfakeDetectionService.createDetection(1L, null, file);

        verify(deepfakeDetector).detect(eq(detection.getId()), eq("/uploads/deepfake-detections/generated.mp4"), eq(true));
    }

    @Test
    void 지원하지_않는_파일_형식이면_예외가_발생한다() {
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> deepfakeDetectionService.createDetection(1L, null, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void 파일_크기가_100MB를_초과하면_예외가_발생한다() {
        byte[] tooLarge = new byte[(int) (100L * 1024 * 1024) + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.mp4", "video/mp4", tooLarge);

        assertThatThrownBy(() -> deepfakeDetectionService.createDetection(1L, null, file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void 비로그인_사용자는_익명_식별자_없이_업로드할_수_없다() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TestFileBytes.PNG);

        assertThatThrownBy(() -> deepfakeDetectionService.createDetection(null, " ", file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANONYMOUS_ID_REQUIRED);
    }

    @Test
    void 비로그인_사용자는_5회_미만이면_업로드할_수_있다() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TestFileBytes.PNG);
        when(deepfakeDetectionRepository.countByAnonymousId("anon-1")).thenReturn(4L);
        when(fileStorage.store(any(MultipartFile.class), anyString())).thenReturn("/uploads/deepfake-detections/generated.png");
        when(deepfakeDetectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeepfakeDetection detection = deepfakeDetectionService.createDetection(null, "anon-1", file);

        assertThat(detection.getUserId()).isNull();
        assertThat(detection.getAnonymousId()).isEqualTo("anon-1");
    }

    @Test
    void 비로그인_사용자는_5회를_초과하면_업로드할_수_없다() {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", TestFileBytes.PNG);
        when(deepfakeDetectionRepository.countByAnonymousId("anon-1")).thenReturn(5L);

        assertThatThrownBy(() -> deepfakeDetectionService.createDetection(null, "anon-1", file))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ANONYMOUS_DETECTION_LIMIT_EXCEEDED);
    }

    @Test
    void 존재하는_소유자가_조회하면_결과를_반환한다() {
        DeepfakeDetection detection = DeepfakeDetection.builder()
                .userId(1L).fileUrl("/uploads/deepfake-detections/generated.png").build();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));

        DeepfakeDetection result = deepfakeDetectionService.getDetection(1L, null, 1L);

        assertThat(result).isEqualTo(detection);
    }

    @Test
    void 존재하지_않는_탐지면_예외가_발생한다() {
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deepfakeDetectionService.getDetection(1L, null, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 소유자가_아니면_예외가_발생한다() {
        DeepfakeDetection detection = DeepfakeDetection.builder()
                .userId(2L).fileUrl("/uploads/deepfake-detections/generated.png").build();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));

        assertThatThrownBy(() -> deepfakeDetectionService.getDetection(1L, null, 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    @Test
    void 익명_사용자가_자신의_식별자로_조회하면_결과를_반환한다() {
        DeepfakeDetection detection = DeepfakeDetection.builder()
                .anonymousId("anon-1").fileUrl("/uploads/deepfake-detections/generated.png").build();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));

        DeepfakeDetection result = deepfakeDetectionService.getDetection(null, "anon-1", 1L);

        assertThat(result).isEqualTo(detection);
    }

    @Test
    void 익명_사용자가_다른_식별자로_조회하면_예외가_발생한다() {
        DeepfakeDetection detection = DeepfakeDetection.builder()
                .anonymousId("anon-1").fileUrl("/uploads/deepfake-detections/generated.png").build();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));

        assertThatThrownBy(() -> deepfakeDetectionService.getDetection(null, "anon-2", 1L))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }
}
