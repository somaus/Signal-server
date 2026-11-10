package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.riskassessment.dto.response.RiskFactorResponse;
import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.global.ai.VisionCompletionClient;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class LlmRiskAnalyzerLanguageTest {

    private static final String FACE_RESPONSE = """
            {
              "faceDetected": true,
              "faceBox": {"x":0.2,"y":0.1,"width":0.4,"height":0.5},
              "imageQualityScore": 80,
              "imageQualityNote": "High resolution.",
              "faceExposureScore": 90,
              "faceExposureNote": "The face is clearly visible.",
              "reverseSearchRiskScore": 30,
              "reverseSearchRiskNote": "The background is generic."
            }
            """;

    @Mock
    private VisionCompletionClient visionCompletionClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearLanguage() {
        LanguageContext.clear();
    }

    private MockMultipartFile samplePngImage() throws IOException {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new MockMultipartFile("image", "photo.png", "image/png", out.toByteArray());
    }

    @Test
    void 영어_요청이면_프롬프트에_영어_지시를_더하고_정형_문구도_영어로_만든다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn(FACE_RESPONSE);
        LanguageContext.set(ResponseLanguage.EN);

        RiskAnalysisResult result = analyzer.analyze(samplePngImage());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(visionCompletionClient).complete(any(byte[].class), anyString(), systemPrompt.capture(), anyString());
        assertThat(systemPrompt.getValue()).contains("Write ALL user-facing natural-language text").contains("English");
        assertThat(result.factors()).extracting(RiskFactor::getType)
                .containsExactly("IMAGE_QUALITY", "FACE_EXPOSURE", "METADATA_LEAK", "REVERSE_SEARCH_RISK");
        assertThat(result.factors()).extracting(RiskFactor::getLabel)
                .containsExactly("Image quality", "Face exposure", "Metadata exposure", "Exposure likelihood (estimate)");
        assertThat(result.factors().get(0).getDescription()).isEqualTo("High resolution. (score: 80)");
        assertThat(result.factors().get(2).getDescription())
                .isEqualTo("No location (GPS) metadata was found in the image file. (score: 10)");
        assertThat(result.factors().get(3).getDescription())
                .startsWith("This is an AI estimate based on the image's features, not the result of an actual reverse image search.");
        assertThat(result.recommendations())
                .isEqualTo(RiskTexts.recommendations(result.overallRiskLevel(), ResponseLanguage.EN));
    }

    @Test
    void 언어가_지정되지_않으면_기존_한국어_프롬프트와_문구를_그대로_쓴다() throws IOException {
        LlmRiskAnalyzer analyzer = new LlmRiskAnalyzer(visionCompletionClient, objectMapper, true);
        when(visionCompletionClient.complete(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn(FACE_RESPONSE);

        RiskAnalysisResult result = analyzer.analyze(samplePngImage());

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(visionCompletionClient).complete(any(byte[].class), anyString(), systemPrompt.capture(), anyString());
        assertThat(systemPrompt.getValue()).doesNotContain("[Response language]");
        assertThat(result.factors()).extracting(RiskFactor::getLabel)
                .containsExactly("이미지 품질", "얼굴 노출도", "메타데이터 노출", "노출 가능성(추정)");
        assertThat(result.factors().get(0).getDescription()).isEqualTo("High resolution. (점수: 80)");
        assertThat(result.factors().get(3).getDescription()).startsWith("실제 역이미지 검색 결과가 아니라");
    }

    @Test
    void 응답의_label은_저장된_이름이_아니라_요청_언어로_내려간다() {
        RiskFactor stored = RiskFactor.builder()
                .type("FACE_EXPOSURE").label("얼굴 노출도").score(90).description("정면 노출 (점수: 90)").build();

        LanguageContext.set(ResponseLanguage.JA);
        assertThat(RiskFactorResponse.from(stored).label()).isEqualTo("顔の露出度");

        LanguageContext.set(ResponseLanguage.ZH_CN);
        assertThat(RiskFactorResponse.from(stored).label()).isEqualTo("面部暴露程度");

        LanguageContext.clear();
        assertThat(RiskFactorResponse.from(stored).label()).isEqualTo("얼굴 노출도");
        assertThat(RiskFactorResponse.from(stored).description()).isEqualTo("정면 노출 (점수: 90)");
    }

    @Test
    void 알_수_없는_type은_저장된_label을_그대로_내려준다() {
        RiskFactor stored = RiskFactor.builder()
                .type("NEW_FACTOR").label("새 요인").score(10).description("설명").build();

        LanguageContext.set(ResponseLanguage.EN);

        assertThat(RiskFactorResponse.from(stored).label()).isEqualTo("새 요인");
    }
}
