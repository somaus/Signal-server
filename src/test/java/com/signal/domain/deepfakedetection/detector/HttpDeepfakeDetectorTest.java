package com.signal.domain.deepfakedetection.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.entity.DetectionBand;
import com.signal.domain.deepfakedetection.entity.Evidence;
import com.signal.domain.deepfakedetection.entity.DetectionFailureReason;
import com.signal.domain.deepfakedetection.entity.Verdict;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.file.FileStorage;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class HttpDeepfakeDetectorTest {

    private static final String FILE_URL = "/uploads/deepfake-detections/original.png";
    private static final String DETECT_URL = "http://detect:8000/detect";

    @Mock
    private FileStorage fileStorage;

    @Mock
    private DeepfakeDetectionRepository deepfakeDetectionRepository;

    private MockRestServiceServer server;
    private HttpDeepfakeDetector detector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://detect:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        detector = new HttpDeepfakeDetector(fileStorage, deepfakeDetectionRepository, builder.build());
    }

    private DeepfakeDetection stubDetection() {
        DeepfakeDetection detection = DeepfakeDetection.builder().userId(1L).fileUrl(FILE_URL).build();
        when(deepfakeDetectionRepository.findById(1L)).thenReturn(Optional.of(detection));
        return detection;
    }

    private void stubFileLoadAndStore() {
        when(fileStorage.load(FILE_URL)).thenReturn(new byte[]{1, 2, 3});
        when(fileStorage.store(any(byte[].class), eq("deepfake-detections")))
                .thenReturn("/uploads/deepfake-detections/highlighted.png");
    }

    private static String scoreBody(String score) {
        return "{\"score\":" + score + ",\"model\":\"cf\",\"modelVersion\":\"onnx-fp32+abc\",\"width\":1024,\"height\":768}";
    }

    @Test
    void 탐지_서비스가_성공하면_점수로_완료_처리한다() {
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        server.expect(requestTo(DETECT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("multipart/form-data")))
                .andRespond(withSuccess(scoreBody("0.91"), MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        server.verify();
        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(detection.getVerdict()).isEqualTo(Verdict.FAKE);
        assertThat(detection.getBand()).isEqualTo("HIGH");
        assertThat(detection.getAssessmentBasis()).isEqualTo("MODEL");
        assertThat(detection.getModelName()).isEqualTo("cf");
        assertThat(detection.getModelVersion()).isEqualTo("onnx-fp32+abc");
        assertThat(detection.getRiskScore()).isEqualTo(91);
        assertThat(detection.getConfidence()).isEqualTo(0.91);
        assertThat(detection.getEvidences()).isEmpty();
        assertThat(detection.getHighlightedResultUrl()).isEqualTo("/uploads/deepfake-detections/highlighted.png");
        assertThat(detection.getFallbackUsed()).isFalse();
        assertThat(detection.getFailureReason()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "0.0, 0, REAL, 1.0",
            "0.09, 9, REAL, 0.91",
            "0.10, 10, SUSPICIOUS, 0.9",
            "0.29, 29, SUSPICIOUS, 0.71",
            "0.50, 50, SUSPICIOUS, 0.5",
            "0.70, 70, SUSPICIOUS, 0.7",
            "0.71, 71, FAKE, 0.71",
            "1.0, 100, FAKE, 1.0"
    })
    void 점수는_riskScore와_등급에_따른_verdict로_바뀐다(String score, int riskScore, Verdict verdict, double confidence) {
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(scoreBody(score), MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(detection.getRiskScore()).isEqualTo(riskScore);
        assertThat(detection.getVerdict()).isEqualTo(verdict);
        assertThat(detection.getConfidence()).isEqualTo(confidence);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"score\":1.5}", "{\"score\":-0.1}", "{}", "null"})
    void 점수가_없거나_범위를_벗어나면_분석하지_못함으로_처리한다(String body) {
        DeepfakeDetection detection = stubDetection();
        when(fileStorage.load(FILE_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.AI_SERVER_ERROR);
        assertThat(detection.getVerdict()).isNull();
    }

    @Test
    void 탐지_서비스가_오류를_응답하면_AI_SERVER_ERROR로_실패_처리한다() {
        DeepfakeDetection detection = stubDetection();
        when(fileStorage.load(FILE_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo(DETECT_URL)).andRespond(withServerError());

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.AI_SERVER_ERROR);
    }

    @Test
    void 이미지를_읽지_못했다는_응답도_실패_처리한다() {
        DeepfakeDetection detection = stubDetection();
        when(fileStorage.load(FILE_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo(DETECT_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.AI_SERVER_ERROR);
    }

    @Test
    void 응답이_지연돼_시간이_초과되면_TIMEOUT으로_실패_처리한다() {
        DeepfakeDetection detection = stubDetection();
        when(fileStorage.load(FILE_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo(DETECT_URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.TIMEOUT);
    }

    @Test
    void 파일을_읽지_못해도_예외를_밖으로_내지_않고_실패_처리한다() {
        DeepfakeDetection detection = stubDetection();
        when(fileStorage.load(FILE_URL)).thenThrow(new IllegalStateException("파일 없음"));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.AI_SERVER_ERROR);
    }

    @Test
    void 영상은_탐지_서비스를_호출하지_않고_VIDEO_NOT_SUPPORTED로_처리한다() {
        DeepfakeDetection detection = stubDetection();

        detector.detect(1L, "/uploads/deepfake-detections/video.mp4", true);

        server.verify();
        verifyNoInteractions(fileStorage);
        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(detection.getFailureReason()).isEqualTo(DetectionFailureReason.VIDEO_NOT_SUPPORTED);
    }

    private HttpDeepfakeDetector detectorWith(DeepfakeExplainer explainer) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://detect:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        return new HttpDeepfakeDetector(fileStorage, deepfakeDetectionRepository, builder.build(),
                new DetectionBandPolicy(10, 70, 256), explainer);
    }

    @ParameterizedTest
    @CsvSource({
            "0.05, LOW", "0.09, LOW", "0.10, UNCERTAIN", "0.29, UNCERTAIN", "0.30, UNCERTAIN", "0.50, UNCERTAIN", "0.70, UNCERTAIN", "0.71, HIGH", "0.99, HIGH"
    })
    void 점수는_등급으로_바뀐다(String score, String band) {
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(scoreBody(score), MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getBand()).isEqualTo(band);
        assertThat(detection.getAssessmentBasis()).isEqualTo("MODEL");
        assertThat(detection.getUndeterminedReason()).isNull();
    }

    @Test
    void 이미지가_너무_작으면_판단_불가이고_점수_숫자를_내지_않는다() {
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        String smallImage = "{\"score\":0.95,\"model\":\"cf\",\"modelVersion\":\"v\",\"width\":200,\"height\":300}";
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(smallImage, MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(detection.getBand()).isEqualTo("UNDETERMINED");
        assertThat(detection.getUndeterminedReason()).isEqualTo("LOW_RESOLUTION");
        assertThat(detection.getVerdict()).isNull();
        assertThat(detection.getRiskScore()).isNull();
        assertThat(detection.getConfidence()).isNull();
        assertThat(detection.getModelVersion()).isEqualTo("v");
    }

    @Test
    void 크기를_모르면_해상도로_판단_불가로_만들지_않는다() {
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        server.expect(requestTo(DETECT_URL))
                .andRespond(withSuccess("{\"score\":0.05,\"model\":\"cf\"}", MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getBand()).isEqualTo("LOW");
    }

    @Test
    void 생성_도구의_흔적이_있으면_점수가_낮아도_높음이고_점수_숫자를_내지_않는다() {
        DeepfakeDetection detection = stubDetection();
        byte[] marked = ("0123456789abcdef http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia")
                .getBytes(StandardCharsets.ISO_8859_1);
        when(fileStorage.load(FILE_URL)).thenReturn(marked);
        when(fileStorage.store(any(byte[].class), eq("deepfake-detections")))
                .thenReturn("/uploads/deepfake-detections/highlighted.png");
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(scoreBody("0.05"), MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getBand()).isEqualTo("HIGH");
        assertThat(detection.getAssessmentBasis()).isEqualTo("PROVENANCE");
        assertThat(detection.getProvenanceSignals()).containsExactly("AI_SOURCE_DECLARED");
        assertThat(detection.getVerdict()).isEqualTo(Verdict.FAKE);
        assertThat(detection.getRiskScore()).isNull();
        assertThat(detection.getConfidence()).isNull();
    }

    @Test
    void 일부를_AI로_편집했다는_표시만_있으면_높음이_아니라_불확실이고_점수_숫자를_내지_않는다() {
        DeepfakeDetection detection = stubDetection();
        byte[] edited = ("0123456789abcdef http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia")
                .getBytes(StandardCharsets.ISO_8859_1);
        when(fileStorage.load(FILE_URL)).thenReturn(edited);
        when(fileStorage.store(any(byte[].class), eq("deepfake-detections")))
                .thenReturn("/uploads/deepfake-detections/highlighted.png");
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(scoreBody("0.05"), MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        assertThat(detection.getBand()).isEqualTo("UNCERTAIN");
        assertThat(detection.getAssessmentBasis()).isEqualTo("PROVENANCE");
        assertThat(detection.getProvenanceSignals()).containsExactly("AI_EDIT_DECLARED");
        assertThat(detection.getVerdict()).isEqualTo(Verdict.SUSPICIOUS);
        assertThat(detection.getRiskScore()).isNull();
        assertThat(detection.getConfidence()).isNull();
    }

    @Test
    void 설명기는_정해진_등급에_근거만_붙이고_등급을_바꾸지_못한다() {
        DeepfakeExplainer explainer = mock(DeepfakeExplainer.class);
        Evidence evidence = Evidence.builder().type("OTHER").description("경계가 흐릿해 보일 수 있습니다.")
                .region(com.signal.domain.deepfakedetection.entity.Region.builder().x(1).y(2).width(3).height(4).build())
                .build();
        when(explainer.explain(any(byte[].class), eq("image/jpeg"), eq(DetectionBand.UNCERTAIN), eq(1024), eq(768)))
                .thenReturn(List.of(evidence));
        HttpDeepfakeDetector withExplainer = detectorWith(explainer);
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(scoreBody("0.50"), MediaType.APPLICATION_JSON));

        withExplainer.detect(1L, FILE_URL, false);

        assertThat(detection.getBand()).isEqualTo("UNCERTAIN");
        assertThat(detection.getEvidences()).containsExactly(evidence);
    }

    @Test
    void 설명기가_실패해도_탐지_결과는_그대로_나간다() {
        DeepfakeExplainer explainer = mock(DeepfakeExplainer.class);
        when(explainer.explain(any(byte[].class), any(), any(DetectionBand.class), eq(1024), eq(768)))
                .thenThrow(new IllegalStateException("LLM 오류"));
        HttpDeepfakeDetector withExplainer = detectorWith(explainer);
        DeepfakeDetection detection = stubDetection();
        stubFileLoadAndStore();
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(scoreBody("0.90"), MediaType.APPLICATION_JSON));

        withExplainer.detect(1L, FILE_URL, false);

        assertThat(detection.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(detection.getBand()).isEqualTo("HIGH");
        assertThat(detection.getEvidences()).isEmpty();
    }

    @Test
    void 분석에_실패하면_설명기를_부르지_않는다() {
        DeepfakeExplainer explainer = mock(DeepfakeExplainer.class);
        HttpDeepfakeDetector withExplainer = detectorWith(explainer);
        stubDetection();
        when(fileStorage.load(FILE_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo(DETECT_URL)).andRespond(withServerError());

        withExplainer.detect(1L, FILE_URL, false);

        verify(explainer, never()).explain(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
