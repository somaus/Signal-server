package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.riskassessment.dto.response.FaceResponse;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import com.signal.domain.riskassessment.dto.response.FaceResponse;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class LlmRiskAnalyzerTest {

    @Mock
    private VisionCompletionClient visionCompletionClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMultipartFile samplePngImage() throws IOException {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("image", "photo.png", "image/png", out.toByteArray());
    }

    @Test
    void LLM이_얼굴을_검출하면_요인_4개와_얼굴_정보를_포함한_결과를_반환한다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("""
                        {
                          "faceDetected": true,
                          "faceBox": {"x":0.2,"y":0.1,"width":0.4,"height":0.5},
                          "imageQualityScore": 80,
                          "imageQualityNote": "해상도가 높습니다",
                          "faceExposureScore": 90,
                          "faceExposureNote": "정면으로 노출되어 있습니다",
                          "reverseSearchRiskScore": 30,
                          "reverseSearchRiskNote": "배경이 평범합니다"
                        }
                        """);

        RiskAnalysisResult result = analyzer.analyze(samplePngImage());

        assertThat(result.faceDetected()).isTrue();
        assertThat(result.factors()).hasSize(4);
        assertThat(result.faces()).hasSize(1);
        assertThat(result.overallRiskLevel()).isNotNull();
        assertThat(result.recommendations()).isNotEmpty();
        assertThat(result.fallbackUsed()).isFalse();
        // 얼굴 박스가 실제 이미지(400x300) 픽셀 좌표로 환산되었는지 확인
        assertThat(result.faces().get(0).getBoundingBox().getX()).isEqualTo((int) Math.round(0.2 * 400));
        assertThat(result.faces().get(0).getBoundingBox().getY()).isEqualTo((int) Math.round(0.1 * 300));
    }

    @Test
    void 얼굴은_있지만_좌표를_못_받으면_가운데_박스를_지어내지_않고_위치를_비워_둔다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"faceDetected": true, "faceBox": null, "imageQualityScore": 80,
                         "imageQualityNote": "a", "faceExposureScore": 90, "faceExposureNote": "b",
                         "reverseSearchRiskScore": 30, "reverseSearchRiskNote": "c"}
                        """);

        RiskAnalysisResult result = analyzer.analyze(samplePngImage());

        assertThat(result.faceDetected()).isTrue();
        assertThat(result.faces()).hasSize(1);
        assertThat(result.faces().get(0).getBoundingBox()).isNull();
        FaceResponse response = FaceResponse.from(result.faces().get(0));
        assertThat(response.positionKnown()).isFalse();
        assertThat(response.boundingBox()).isNull();
    }

    @Test
    void 이미지_픽셀_크기를_읽지_못하면_좌표를_받아도_위치를_비워_둔다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"faceDetected": true, "faceBox": {"x":0.2,"y":0.1,"width":0.4,"height":0.5},
                         "imageQualityScore": 80, "imageQualityNote": "a", "faceExposureScore": 90,
                         "faceExposureNote": "b", "reverseSearchRiskScore": 30, "reverseSearchRiskNote": "c"}
                        """);
        MockMultipartFile unreadable =
                new MockMultipartFile("image", "broken.png", "image/png", new byte[] {1, 2, 3});

        RiskAnalysisResult result = analyzer.analyze(unreadable);

        assertThat(result.faces()).hasSize(1);
        assertThat(result.faces().get(0).getBoundingBox()).isNull();
        assertThat(FaceResponse.from(result.faces().get(0)).positionKnown()).isFalse();
    }

    @Test
    void 좌표를_받으면_positionKnown이_true다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"faceDetected": true, "faceBox": {"x":0.2,"y":0.1,"width":0.4,"height":0.5},
                         "imageQualityScore": 80, "imageQualityNote": "a", "faceExposureScore": 90,
                         "faceExposureNote": "b", "reverseSearchRiskScore": 30, "reverseSearchRiskNote": "c"}
                        """);

        FaceResponse response = FaceResponse.from(analyzer.analyze(samplePngImage()).faces().get(0));

        assertThat(response.positionKnown()).isTrue();
        assertThat(response.boundingBox().x()).isEqualTo(80);
    }

    @Test
    void LLM이_얼굴을_못_찾으면_faceNotDetected를_반환한다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("""
                        {"faceDetected": false, "faceBox": null, "imageQualityScore": 0,
                         "imageQualityNote": "", "faceExposureScore": 0, "faceExposureNote": "",
                         "reverseSearchRiskScore": 0, "reverseSearchRiskNote": ""}
                        """);

        RiskAnalysisResult result = analyzer.analyze(samplePngImage());

        assertThat(result.faceDetected()).isFalse();
        assertThat(result.factors()).isEmpty();
        assertThat(result.faces()).isEmpty();
        // 실제 LLM이 얼굴을 못 찾았다고 답한 것이므로 폴백이 아니다.
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    void LLM_호출이_실패하면_추정_점수_없이_분석_불가로_응답한다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("GROQ_API_KEY가 설정되지 않았습니다."));

        assertThatThrownBy(() -> analyzer.analyze(samplePngImage()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_UNAVAILABLE));
    }

    @Test
    void LLM_호출이_시간초과면_분석_시간_초과로_응답한다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("호출 실패", new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> analyzer.analyze(samplePngImage()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_TIMEOUT));
    }

    @Test
    void vision_llm_비활성화면_LLM을_호출하지_않고_분석_불가로_응답한다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, false);

        assertThatThrownBy(() -> analyzer.analyze(samplePngImage()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_UNAVAILABLE));
        verify(visionCompletionClient, never()).complete(any(), any(), any(), any());
    }
}
