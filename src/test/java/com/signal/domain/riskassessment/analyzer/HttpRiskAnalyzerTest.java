package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpRiskAnalyzerTest {

    private static final String URL = "http://risk:8000/analyze";

    private MockRestServiceServer server;
    private HttpRiskAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://risk:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        analyzer = new HttpRiskAnalyzer(builder.build());
    }

    @AfterEach
    void clearLanguage() {
        LanguageContext.clear();
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("image", "photo.png", "image/png", new byte[] {1, 2, 3});
    }

    private static RiskFactor factor(RiskAnalysisResult result, String type) {
        return result.factors().stream().filter(f -> f.getType().equals(type)).findFirst().orElseThrow();
    }

    private static final String ONE_FACE = """
            {"model":"yunet","modelVersion":"2023mar","width":1000,"height":800,"unknownField":1,
             "faces":[{"box":{"x":100,"y":50,"width":300,"height":300},"confidence":0.95,
                       "areaRatio":0.1125,"facePx":300,"sharpness":350.0,"frontal":1.0,"brightness":120.0}]}
            """;

    @Test
    void 측정값으로_점수를_계산하고_얼굴_위치를_그대로_돌려준다() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(ONE_FACE, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(result.faceDetected()).isTrue();
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(factor(result, "IMAGE_QUALITY").getScore()).isEqualTo(100);
        assertThat(factor(result, "FACE_EXPOSURE").getScore()).isEqualTo(
                (int) Math.round((0.5 * RiskScoring.logScale(0.1125, RiskScoring.AREA_LOW, RiskScoring.AREA_HIGH) + 0.5) * 100));
        assertThat(factor(result, "METADATA_LEAK").getScore()).isEqualTo(10);
        assertThat(result.faces()).hasSize(1);
        assertThat(result.faces().get(0).getBoundingBox().getX()).isEqualTo(100);
        assertThat(result.faces().get(0).getBoundingBox().getWidth()).isEqualTo(300);
        server.verify();
    }

    @Test
    void 역검색_항목은_측정할_수_없어_넣지_않는다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(ONE_FACE, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(result.factors()).extracting(RiskFactor::getType)
                .containsExactly("IMAGE_QUALITY", "FACE_EXPOSURE", "METADATA_LEAK");
    }

    @Test
    void 종합_점수는_항목_가중_합이고_등급과_권고가_따라온다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(ONE_FACE, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        int expected = RiskScoring.overall(factor(result, "IMAGE_QUALITY").getScore(),
                factor(result, "FACE_EXPOSURE").getScore(), factor(result, "METADATA_LEAK").getScore());
        assertThat(result.overallScore()).isEqualTo(expected);
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.fromScore(expected));
        assertThat(result.recommendations()).isNotEmpty();
    }

    @Test
    void 설명에_점수의_근거인_측정값이_들어간다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(ONE_FACE, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(factor(result, "IMAGE_QUALITY").getDescription()).contains("300px").contains("350");
        assertThat(factor(result, "FACE_EXPOSURE").getDescription()).contains("11.3%").contains("100%").contains("1개");
    }

    @Test
    void 요청_언어에_맞춰_설명을_쓴다() {
        LanguageContext.set(ResponseLanguage.EN);
        server.expect(requestTo(URL)).andRespond(withSuccess(ONE_FACE, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(factor(result, "IMAGE_QUALITY").getLabel()).isEqualTo("Image quality");
        assertThat(factor(result, "IMAGE_QUALITY").getDescription()).contains("300px").contains("score");
    }

    @Test
    void 얼굴이_없으면_얼굴_없음으로_응답한다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"model\":\"yunet\",\"modelVersion\":\"2023mar\",\"width\":10,\"height\":10,\"faces\":[]}",
                MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(result.faceDetected()).isFalse();
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.factors()).isEmpty();
    }

    @Test
    void 여러_얼굴은_받은_그대로_큰_순서로_돌려주고_항목은_가장_높은_얼굴_기준이다() {
        StringBuilder faces = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            int size = 40 + i * 10;   // 마지막(i=11)이 가장 크다
            if (i > 0) {
                faces.append(',');
            }
            faces.append("{\"box\":{\"x\":%d,\"y\":0,\"width\":%d,\"height\":%d},\"confidence\":0.9,\"areaRatio\":0.01,\"facePx\":%d,\"sharpness\":100,\"frontal\":0.5}"
                    .formatted(i, size, size, size));
        }
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"model\":\"yunet\",\"modelVersion\":\"2023mar\",\"faceCount\":12,\"faces\":[" + faces + "]}",
                MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        // 개수 제한은 risk-lab에서만 한다. 서버는 받은 얼굴을 모두 돌려준다.
        assertThat(result.faces()).hasSize(12);
        assertThat(result.faces().get(0).getBoundingBox().getWidth()).isEqualTo(150);
        assertThat(result.faces().get(0).getFaceIndex()).isZero();
        assertThat(factor(result, "IMAGE_QUALITY").getDescription()).contains("150px");
        assertThat(factor(result, "FACE_EXPOSURE").getDescription()).contains("12개");
    }

    @Test
    void 얼굴_수_상한으로_일부만_왔어도_검출한_전체_수를_설명에_쓴다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                """
                {"model":"yunet","modelVersion":"2023mar","faceCount":18,
                 "faces":[{"box":{"x":1,"y":1,"width":50,"height":50},"confidence":0.9,"areaRatio":0.01,
                           "facePx":50,"sharpness":100,"frontal":0.5}]}
                """, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(result.faces()).hasSize(1);
        assertThat(factor(result, "FACE_EXPOSURE").getDescription()).contains("18개");
    }

    @Test
    void 얼굴이_선명하고_크고_정면이면_위치_정보가_없어도_종합이_높음에_닿는다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(ONE_FACE, MediaType.APPLICATION_JSON));

        RiskAnalysisResult result = analyzer.analyze(image());

        assertThat(factor(result, "METADATA_LEAK").getScore()).isEqualTo(10);
        assertThat(result.overallScore()).isGreaterThanOrEqualTo(70);
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void 측정_서비스가_이미지를_읽지_못하면_다시_시도하라는_서버_오류가_아니라_파일_형식_오류로_구분한다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> analyzer.analyze(image()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void 측정_서비스가_해상도_초과로_거절하면_파일_크기_오류로_구분한다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));

        assertThatThrownBy(() -> analyzer.analyze(image()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    void 측정_서비스가_바쁘다고_하면_분석하지_못함으로_응답한다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> analyzer.analyze(image()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_UNAVAILABLE));
    }

    @Test
    void 측정_서비스가_실패하면_점수를_지어내지_않고_분석하지_못함으로_응답한다() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> analyzer.analyze(image()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_UNAVAILABLE));
    }

    @Test
    void 타임아웃은_타임아웃으로_구분한다() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.io.IOException(new SocketTimeoutException("read timed out"));
        });

        assertThatThrownBy(() -> analyzer.analyze(image()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_TIMEOUT));
    }

    @Test
    void 응답_본문이_비어_있으면_분석하지_못함() {
        server.expect(requestTo(URL)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> analyzer.analyze(image()))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_UNAVAILABLE));
    }
}
