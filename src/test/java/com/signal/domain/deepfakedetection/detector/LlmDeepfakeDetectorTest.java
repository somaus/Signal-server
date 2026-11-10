package com.signal.domain.deepfakedetection.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.Verdict;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.file.FileStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.springframework.web.client.ResourceAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmDeepfakeDetectorTest {

    @Mock
    private FileStorage fileStorage;

    @Mock
    private DeepfakeDetectionRepository deepfakeDetectionRepository;

    @Mock
    private VisionCompletionClient visionCompletionClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmDeepfakeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LlmDeepfakeDetector(
                fileStorage, deepfakeDetectionRepository, visionCompletionClient, objectMapper, 0, true);
    }

    private DeepfakeDetection sampleDetection() {
        return DeepfakeDetection.builder()
                .userId(1L)
                .fileUrl("/uploads/deepfake-detections/original.png")
                .build();
    }

    private byte[] samplePngBytes() throws IOException {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void LLM_분석에_성공하면_verdict와_근거를_반영해_완료_처리한다() throws IOException {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(samplePngBytes());
        when(fileStorage.store(any(byte[].class), anyString()))
                .thenReturn("/uploads/deepfake-detections/highlighted.png");
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("""
                        {
                          "verdict": "FAKE",
                          "confidence": 0.87,
                          "riskScore": 82,
                          "evidences": [
                            {"type": "FACIAL_ARTIFACT", "description": "얼굴 경계가 부자연스럽습니다.",
                             "region": {"x":0.1,"y":0.1,"width":0.3,"height":0.3}}
                          ]
                        }
                        """);

        detector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(detection.getVerdict()).isEqualTo(Verdict.FAKE);
        assertThat(detection.getConfidence()).isEqualTo(0.87);
        assertThat(detection.getRiskScore()).isEqualTo(82);
        assertThat(detection.getEvidences()).hasSize(1);
        assertThat(detection.getEvidences().get(0).getFrame()).isNull();
        assertThat(detection.getHighlightedResultUrl()).isEqualTo("/uploads/deepfake-detections/highlighted.png");
        assertThat(detection.getFallbackUsed()).isFalse();
        assertThat(detection.getFailureReason()).isNull();
    }

    @Test
    void 얼굴이_없다고_응답하면_NO_FACE_DETECTED로_실패_처리한다() throws IOException {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(samplePngBytes());
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("{\"faceDetected\": false, \"verdict\": \"REAL\", \"confidence\": 0, \"riskScore\": 0, \"evidences\": []}");

        detector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.NO_FACE_DETECTED);
        assertThat(detection.getVerdict()).isNull();
    }

    @Test
    void LLM_호출이_시간초과되면_TIMEOUT으로_실패_처리한다() throws IOException {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(samplePngBytes());
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        detector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.TIMEOUT);
    }

    @Test
    void 영상_입력이면_LLM을_호출하지_않고_분석하지_못함으로_처리한다() throws IOException {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));

        detector.detect(1L, "/uploads/deepfake-detections/original.mp4", true);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.VIDEO_NOT_SUPPORTED);
        assertThat(detection.getVerdict()).isNull();
        verify(visionCompletionClient, never()).complete(any(), any(), any(), any());
    }

    @Test
    void LLM_호출이_실패하면_추정_판정_없이_실패_처리한다() throws IOException {
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(samplePngBytes());
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("GROQ_API_KEY가 설정되지 않았습니다."));

        detector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.AI_SERVER_ERROR);
        assertThat(detection.getVerdict()).isNull();
    }

    @Test
    void vision_llm_비활성화면_LLM을_호출하지_않는다() throws IOException {
        LlmDeepfakeDetector disabledDetector = new LlmDeepfakeDetector(
                fileStorage, deepfakeDetectionRepository, visionCompletionClient, objectMapper, 0, false);
        DeepfakeDetection detection = sampleDetection();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));

        disabledDetector.detect(1L, "/uploads/deepfake-detections/original.png", false);

        verify(visionCompletionClient, never()).complete(any(), any(), any(), any());
        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getVerdict()).isNull();
    }
}
