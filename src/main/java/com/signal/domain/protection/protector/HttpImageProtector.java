package com.signal.domain.protection.protector;

import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.repository.ProtectionRepository;
import com.signal.global.file.FileStorage;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * protection-lab(FastAPI) 서비스의 {@code POST /protect}를 호출해 실제 적대적 노이즈를 삽입하는 구현체.
 * {@code protection.mode=http} 일 때만 활성화되며, 기본값(noise)에서는 {@link NoiseImageProtector}가 사용된다.
 *
 * 실패 시(연결 불가, 타임아웃, 비정상 응답) NoiseImageProtector로 조용히 대체하지 않고 Protection을 FAILED로 표시한다.
 * visualDifference는 응답 헤더 X-PSNR-dB를 RMSE 비율(10^(-PSNR/20), 0~1)로 환산한 값이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "protection.mode", havingValue = "http")
public class HttpImageProtector implements ImageProtector {

    private static final String PROTECTED_IMAGE_DIRECTORY = "protections";
    private static final String PSNR_HEADER = "X-PSNR-dB";

    private final FileStorage fileStorage;
    private final ProtectionRepository protectionRepository;
    private final RestClient restClient;

    @Autowired
    public HttpImageProtector(FileStorage fileStorage,
                              ProtectionRepository protectionRepository,
                              RestClient.Builder restClientBuilder,
                              @Value("${protection.http.base-url:http://localhost:8000}") String baseUrl,
                              @Value("${protection.http.timeout-seconds:300}") long timeoutSeconds) {
        this(fileStorage, protectionRepository, buildClient(restClientBuilder, baseUrl, timeoutSeconds));
    }

    HttpImageProtector(FileStorage fileStorage, ProtectionRepository protectionRepository, RestClient restClient) {
        this.fileStorage = fileStorage;
        this.protectionRepository = protectionRepository;
        this.restClient = restClient;
    }

    private static RestClient buildClient(RestClient.Builder builder, String baseUrl, long timeoutSeconds) {
        // 전역 http.client.read-timeout(10초)은 노이즈 계산(수십 초~수 분)에 짧아 이 클라이언트만 따로 설정한다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    @Async
    public void protect(Long protectionId, String originalImageUrl, ProtectionLevel protectionLevel) {
        try {
            byte[] originalBytes = fileStorage.load(originalImageUrl);

            ResponseEntity<byte[]> response = restClient.post()
                    .uri("/protect")
                    .body(multipartBody(originalBytes, originalImageUrl, protectionLevel))
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] protectedBytes = response.getBody();
            if (protectedBytes == null || protectedBytes.length == 0) {
                throw new IllegalStateException("보호 서비스가 빈 응답을 반환했습니다.");
            }

            double visualDifference = toVisualDifference(response.getHeaders().getFirst(PSNR_HEADER));
            String protectedImageUrl = fileStorage.store(protectedBytes, PROTECTED_IMAGE_DIRECTORY);

            markCompleted(protectionId, protectedImageUrl, visualDifference);
        } catch (Exception e) {
            log.error("이미지 보호 처리 실패(보호 서비스 호출): protectionId={}", protectionId, e);
            markFailed(protectionId);
        }
    }

    private MultiValueMap<String, Object> multipartBody(byte[] bytes, String originalImageUrl, ProtectionLevel level) {
        String filename = filenameOf(originalImageUrl);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("level", level.name());
        return body;
    }

    private static String filenameOf(String url) {
        if (url == null || url.isBlank()) {
            return "image";
        }
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        return name.isBlank() ? "image" : name;
    }

    /** PSNR(dB) → RMSE/255 비율. 높을수록 원본과 많이 다르다. (예: 40dB → 0.010, 30dB → 0.032) */
    static double toVisualDifference(String psnrHeader) {
        if (psnrHeader == null || psnrHeader.isBlank()) {
            throw new IllegalStateException("보호 서비스 응답에 " + PSNR_HEADER + " 헤더가 없습니다.");
        }
        double psnr = Double.parseDouble(psnrHeader.trim());
        double ratio = Math.pow(10, -psnr / 20.0);
        return Math.round(ratio * 1000) / 1000.0;
    }

    private void markCompleted(Long protectionId, String protectedImageUrl, double visualDifference) {
        protectionRepository.findById(protectionId).ifPresent(protection -> {
            protection.complete(protectedImageUrl, visualDifference);
            protectionRepository.save(protection);
        });
    }

    private void markFailed(Long protectionId) {
        protectionRepository.findById(protectionId).ifPresent(protection -> {
            protection.fail();
            protectionRepository.save(protection);
        });
    }
}
