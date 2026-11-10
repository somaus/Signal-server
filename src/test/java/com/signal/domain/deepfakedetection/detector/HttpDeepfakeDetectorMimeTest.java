package com.signal.domain.deepfakedetection.detector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DetectionBand;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.global.file.FileStorage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class HttpDeepfakeDetectorMimeTest {

    private static final String FILE_URL = "/uploads/deepfake-detections/original.png";
    private static final String DETECT_URL = "http://detect:8000/detect";
    private static final String BODY =
            "{\"score\":0.50,\"model\":\"cf\",\"modelVersion\":\"v\",\"width\":1024,\"height\":768}";

    @Mock
    private FileStorage fileStorage;

    @Mock
    private DeepfakeDetectionRepository repository;

    private String mimeTypeSentToExplainer(byte[] fileBytes) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://detect:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DeepfakeExplainer explainer = mock(DeepfakeExplainer.class);
        lenient().when(explainer.explain(any(byte[].class), any(), any(DetectionBand.class), anyInt(), anyInt()))
                .thenReturn(List.of());
        HttpDeepfakeDetector detector = new HttpDeepfakeDetector(fileStorage, repository, builder.build(),
                new DetectionBandPolicy(10, 70, 256), explainer);
        DeepfakeDetection detection = DeepfakeDetection.builder().userId(1L).fileUrl(FILE_URL).build();
        lenient().when(repository.findById(1L)).thenReturn(Optional.of(detection));
        lenient().when(fileStorage.load(FILE_URL)).thenReturn(fileBytes);
        lenient().when(fileStorage.store(any(byte[].class), eq("deepfake-detections")))
                .thenReturn("/uploads/deepfake-detections/highlighted.png");
        server.expect(requestTo(DETECT_URL)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        detector.detect(1L, FILE_URL, false);

        ArgumentCaptor<String> mime = ArgumentCaptor.forClass(String.class);
        verify(explainer).explain(any(byte[].class), mime.capture(), any(DetectionBand.class), anyInt(), anyInt());
        return mime.getValue();
    }

    @Test
    void PNG_시그니처로_시작하면_png로_설명기에_넘긴다() {
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x89, 'P', 'N', 'G'})).isEqualTo("image/png");
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
    }

    @Test
    void 시그니처가_한_바이트라도_다르거나_짧으면_jpeg로_넘긴다() {
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x88, 'P', 'N', 'G'})).isEqualTo("image/jpeg");
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x89, 'Q', 'N', 'G'})).isEqualTo("image/jpeg");
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x89, 'P', 'M', 'G'})).isEqualTo("image/jpeg");
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x89, 'P', 'N', 'H'})).isEqualTo("image/jpeg");
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0x89, 'P', 'N'})).isEqualTo("image/jpeg");
        assertThat(mimeTypeSentToExplainer(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}))
                .isEqualTo("image/jpeg");
    }
}
