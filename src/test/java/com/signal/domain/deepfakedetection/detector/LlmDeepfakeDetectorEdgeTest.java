package com.signal.domain.deepfakedetection.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.Evidence;
import com.signal.domain.deepfakedetection.entity.Region;
import com.signal.domain.deepfakedetection.entity.Verdict;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.file.FileStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmDeepfakeDetectorEdgeTest {

    private static final String URL = "/uploads/deepfake-detections/original.png";

    @Mock
    private FileStorage fileStorage;

    @Mock
    private DeepfakeDetectionRepository repository;

    @Mock
    private VisionCompletionClient client;

    private LlmDeepfakeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LlmDeepfakeDetector(fileStorage, repository, client, new ObjectMapper(), 0, true);
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private DeepfakeDetection run(byte[] fileBytes, String llmResponse) {
        DeepfakeDetection detection = DeepfakeDetection.builder().userId(1L).fileUrl(URL).build();
        when(repository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(fileBytes);
        when(fileStorage.store(any(byte[].class), anyString())).thenReturn("/uploads/deepfake-detections/h.png");
        when(client.complete(any(byte[].class), anyString(), anyString(), anyString())).thenReturn(llmResponse);
        detector.detect(1L, URL, false);
        return detection;
    }

    private static String withEvidence(String evidenceJson) {
        return "{\"verdict\":\"FAKE\",\"confidence\":0.5,\"riskScore\":80,\"evidences\":[" + evidenceJson + "]}";
    }

    @Test
    void 정규화_좌표를_이미지_픽셀로_바꾼다() throws IOException {
        DeepfakeDetection detection = run(png(400, 300), withEvidence(
                "{\"type\":\"OTHER\",\"description\":\"d\",\"region\":{\"x\":0.1,\"y\":0.2,\"width\":0.5,\"height\":0.25}}"));

        Region region = detection.getEvidences().get(0).getRegion();
        assertThat(region.getX()).isEqualTo(40);
        assertThat(region.getY()).isEqualTo(60);
        assertThat(region.getWidth()).isEqualTo(200);
        assertThat(region.getHeight()).isEqualTo(75);
    }

    @Test
    void 범위를_벗어난_좌표는_0과_1로_자르고_크기는_최소_1이다() throws IOException {
        DeepfakeDetection detection = run(png(400, 300), withEvidence(
                "{\"type\":\"OTHER\",\"description\":\"d\",\"region\":{\"x\":-0.5,\"y\":2.0,\"width\":0.0,\"height\":3.0}}"));

        Region region = detection.getEvidences().get(0).getRegion();
        assertThat(region.getX()).isEqualTo(0);
        assertThat(region.getY()).isEqualTo(300);
        assertThat(region.getWidth()).isEqualTo(1);
        assertThat(region.getHeight()).isEqualTo(300);
    }

    @Test
    void 위치가_없으면_이미지_가운데_영역으로_근사한다() throws IOException {
        DeepfakeDetection detection = run(png(400, 300), withEvidence("{\"type\":\"OTHER\",\"description\":\"d\"}"));

        Region region = detection.getEvidences().get(0).getRegion();
        assertThat(region.getX()).isEqualTo(100);
        assertThat(region.getY()).isEqualTo(75);
        assertThat(region.getWidth()).isEqualTo(200);
        assertThat(region.getHeight()).isEqualTo(150);
    }

    @Test
    void 작거나_읽을_수_없는_이미지는_200px_기준_가운데_영역으로_근사한다() throws IOException {
        Region small = run(png(100, 100), withEvidence("{\"type\":\"OTHER\",\"description\":\"d\"}"))
                .getEvidences().get(0).getRegion();

        assertThat(small.getX()).isEqualTo(50);
        assertThat(small.getY()).isEqualTo(50);
        assertThat(small.getWidth()).isEqualTo(100);
        assertThat(small.getHeight()).isEqualTo(100);
    }

    @Test
    void 읽을_수_없는_이미지도_예외_없이_근거를_붙여_완료한다() {
        DeepfakeDetection detection = run(new byte[] {1, 2, 3, 4}, withEvidence(
                "{\"type\":\"OTHER\",\"description\":\"d\",\"region\":{\"x\":0.1,\"y\":0.1,\"width\":0.3,\"height\":0.3}}"));

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        Region region = detection.getEvidences().get(0).getRegion();
        assertThat(region.getX()).isEqualTo(50);
        assertThat(region.getWidth()).isEqualTo(100);
    }

    @Test
    void 근거는_최대_5개까지만_담고_빈_설명은_건너뛴다() throws IOException {
        StringBuilder six = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            six.append(i > 1 ? "," : "").append("{\"type\":\"OTHER\",\"description\":\"근거").append(i).append("\"}");
        }
        DeepfakeDetection capped = run(png(400, 300), withEvidence(six.toString()));
        assertThat(capped.getEvidences()).hasSize(5);
        assertThat(capped.getEvidences().get(4).getDescription()).isEqualTo("근거5");
    }

    @Test
    void 정확히_5개면_모두_담는다() throws IOException {
        StringBuilder five = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            five.append(i > 1 ? "," : "").append("{\"type\":\"OTHER\",\"description\":\"근거").append(i).append("\"}");
        }

        assertThat(run(png(400, 300), withEvidence(five.toString())).getEvidences()).hasSize(5);
    }

    @Test
    void 설명이_비었거나_없는_근거는_버리고_설명과_유형을_다듬는다() throws IOException {
        DeepfakeDetection detection = run(png(400, 300), withEvidence(
                "{\"type\":\"OTHER\",\"description\":\"  \"},"
                        + "{\"type\":\"OTHER\"},"
                        + "null,"
                        + "{\"type\":\" facial_artifact \",\"description\":\"  경계가 이상함  \"},"
                        + "{\"description\":\"유형 없음\"},"
                        + "{\"type\":\"  \",\"description\":\"유형 공백\"}"));

        assertThat(detection.getEvidences()).extracting(Evidence::getType)
                .containsExactly("FACIAL_ARTIFACT", "OTHER", "OTHER");
        assertThat(detection.getEvidences()).extracting(Evidence::getDescription)
                .containsExactly("경계가 이상함", "유형 없음", "유형 공백");
    }

    @Test
    void 판정_문자열은_공백과_대소문자를_무시하고_모르는_값은_위험_점수로_정한다() throws IOException {
        assertThat(run(png(400, 300), "{\"verdict\":\" fake \",\"confidence\":0.5,\"riskScore\":5}").getVerdict())
                .isEqualTo(Verdict.FAKE);
    }

    @Test
    void 판정이_없거나_엉뚱하면_위험_점수_구간으로_정한다() throws IOException {
        assertThat(run(png(400, 300), "{\"verdict\":\"MAYBE\",\"confidence\":0.5,\"riskScore\":85}").getVerdict())
                .isEqualTo(Verdict.FAKE);
    }

    @Test
    void 위험_점수와_확신도는_범위로_자른다() throws IOException {
        DeepfakeDetection high = run(png(400, 300), "{\"verdict\":\"FAKE\",\"confidence\":1.5,\"riskScore\":150}");
        assertThat(high.getRiskScore()).isEqualTo(100);
        assertThat(high.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void 확신도는_소수_둘째_자리로_반올림한다() throws IOException {
        DeepfakeDetection detection = run(png(400, 300), "{\"verdict\":\"REAL\",\"confidence\":0.876,\"riskScore\":10}");

        assertThat(detection.getConfidence()).isEqualTo(0.88);
    }

    @Test
    void 음수_점수와_확신도는_0으로_자른다() throws IOException {
        DeepfakeDetection detection = run(png(400, 300), "{\"verdict\":\"REAL\",\"confidence\":-1,\"riskScore\":-5}");

        assertThat(detection.getRiskScore()).isEqualTo(0);
        assertThat(detection.getConfidence()).isEqualTo(0.0);
    }

    @Test
    void PNG_시그니처가_8바이트_이상이면_png로_아니면_jpeg로_보낸다() {
        ArgumentCaptor<String> mime = ArgumentCaptor.forClass(String.class);
        byte[] pngHeader = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        run(pngHeader, "{\"verdict\":\"REAL\",\"confidence\":0.5,\"riskScore\":1}");
        verify(client).complete(any(byte[].class), mime.capture(), anyString(), anyString());

        assertThat(mime.getValue()).isEqualTo("image/png");
    }

    @Test
    void PNG_시그니처가_짧거나_다르면_jpeg로_보낸다() {
        ArgumentCaptor<String> mime = ArgumentCaptor.forClass(String.class);
        byte[] shortHeader = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A};
        run(shortHeader, "{\"verdict\":\"REAL\",\"confidence\":0.5,\"riskScore\":1}");
        verify(client).complete(any(byte[].class), mime.capture(), anyString(), anyString());

        assertThat(mime.getValue()).isEqualTo("image/jpeg");
    }

    @Test
    void HttpTimeout이_원인이면_TIMEOUT_그_밖의_오류면_AI_SERVER_ERROR로_실패한다() throws IOException {
        DeepfakeDetection detection = DeepfakeDetection.builder().userId(1L).fileUrl(URL).build();
        when(repository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(png(400, 300));
        when(client.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("감싼 오류", new HttpTimeoutException("timeout")));

        detector.detect(1L, URL, false);

        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.TIMEOUT);
    }

    @Test
    void 시간_초과가_아닌_오류는_AI_SERVER_ERROR다() throws IOException {
        DeepfakeDetection detection = DeepfakeDetection.builder().userId(1L).fileUrl(URL).build();
        when(repository.findById(1L)).thenReturn(Optional.of(detection));
        when(fileStorage.load(anyString())).thenReturn(png(400, 300));
        when(client.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("서버 오류"));

        detector.detect(1L, URL, false);

        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.AI_SERVER_ERROR);
    }
}
