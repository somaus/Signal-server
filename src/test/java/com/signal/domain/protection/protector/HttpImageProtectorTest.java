package com.signal.domain.protection.protector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.entity.ProtectionStatus;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.global.file.FileStorage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class HttpImageProtectorTest {

    private static final String ORIGINAL_URL = "/uploads/risk-assessments/original.png";

    @Mock
    private FileStorage fileStorage;

    @Mock
    private ProtectionRepository protectionRepository;

    private MockRestServiceServer server;
    private HttpImageProtector protector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://protection-lab:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        protector = new HttpImageProtector(fileStorage, protectionRepository, builder.build());
    }

    private Protection sampleProtection() {
        return Protection.builder()
                .userId(1L)
                .assessmentId(10L)
                .protectionLevel(ProtectionLevel.NORMAL)
                .originalImageUrl(ORIGINAL_URL)
                .build();
    }

    @Test
    void 보호_서비스가_성공하면_보호이미지를_저장하고_PSNR을_시각적_차이로_환산한다() {
        Protection protection = sampleProtection();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load(ORIGINAL_URL)).thenReturn(new byte[]{1, 2, 3});
        when(fileStorage.store(any(byte[].class), eq("protections")))
                .thenReturn("/uploads/protections/generated.png");
        server.expect(requestTo("http://protection-lab:8000/protect"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("multipart/form-data")))
                .andRespond(withSuccess(new byte[]{9, 9, 9}, MediaType.IMAGE_PNG).header("X-PSNR-dB", "40.00"));

        protector.protect(1L, ORIGINAL_URL, ProtectionLevel.NORMAL);

        server.verify();
        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.COMPLETED);
        assertThat(protection.getProtectedImageUrl()).isEqualTo("/uploads/protections/generated.png");
        assertThat(protection.getVisualDifference()).isEqualTo(0.01);
        verify(protectionRepository).save(protection);
    }

    @Test
    void 보호_서비스가_오류를_반환하면_다른_구현으로_대체하지_않고_FAILED로_저장한다() {
        Protection protection = sampleProtection();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load(ORIGINAL_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo("http://protection-lab:8000/protect")).andRespond(withServerError());

        protector.protect(1L, ORIGINAL_URL, ProtectionLevel.STRONG);

        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.FAILED);
        assertThat(protection.getProtectedImageUrl()).isNull();
        verify(fileStorage, never()).store(any(byte[].class), anyString());
        verify(protectionRepository).save(protection);
    }

    @Test
    void PSNR_헤더가_없으면_FAILED로_저장한다() {
        Protection protection = sampleProtection();
        when(protectionRepository.findById(1L)).thenReturn(Optional.of(protection));
        when(fileStorage.load(ORIGINAL_URL)).thenReturn(new byte[]{1, 2, 3});
        server.expect(requestTo("http://protection-lab:8000/protect"))
                .andRespond(withSuccess(new byte[]{9}, MediaType.IMAGE_PNG));

        protector.protect(1L, ORIGINAL_URL, ProtectionLevel.LIGHT);

        assertThat(protection.getStatus()).isEqualTo(ProtectionStatus.FAILED);
    }

    @Test
    void PSNR을_RMSE_비율로_환산한다() {
        assertThat(HttpImageProtector.toVisualDifference("40")).isEqualTo(0.01);
        assertThat(HttpImageProtector.toVisualDifference("30")).isEqualTo(0.032);
        assertThatThrownBy(() -> HttpImageProtector.toVisualDifference(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
