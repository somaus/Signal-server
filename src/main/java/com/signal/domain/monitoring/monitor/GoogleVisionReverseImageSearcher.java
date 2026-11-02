package com.signal.domain.monitoring.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.signal.domain.monitoring.entity.MatchType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Google Cloud Vision의 웹 감지(WEB_DETECTION)로 같은 이미지가 실린 페이지를 찾는다. 월 1,000건까지 무료이고
 * 이미지를 base64로 보내므로 업로드 파일을 외부에 공개할 필요가 없다. 얼굴을 비교하는 것이 아니라
 * 이미지 단위 일치를 찾으므로, 같은 사진이 아닌 "같은 얼굴의 다른 사진"은 찾지 못한다.
 */
@Component
@Primary   // 검색 서비스 구현체가 여러 개일 때 모니터링은 이쪽을 쓴다(웹 검색은 @Qualifier("serpApi"))
@ConditionalOnProperty(name = "monitoring.mode", havingValue = "search")
public class GoogleVisionReverseImageSearcher implements ReverseImageSearcher {

    static final int MAX_RESULTS = 20;
    static final int MAX_URL_LENGTH = 2048;   // detections.source_url / thumbnail_url 컬럼 길이

    private final RestClient restClient;

    public GoogleVisionReverseImageSearcher(RestClient.Builder builder,
                                            @Value("${monitoring.search.google-vision.api-key:}") String apiKey,
                                            @Value("${monitoring.search.google-vision.base-url:https://vision.googleapis.com}") String baseUrl,
                                            @Value("${monitoring.search.timeout-seconds:30}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "monitoring.mode=search 에는 GOOGLE_VISION_API_KEY가 필요합니다. 키가 없으면 MONITORING_MODE=stub 을 쓰세요.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        // 키는 URL(로그에 남을 수 있음)이 아닌 헤더로 보낸다.
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("x-goog-api-key", apiKey).build();
    }

    @Override
    public List<SearchHit> search(byte[] image) {
        Map<String, Object> body = Map.of("requests", List.of(Map.of(
                "image", Map.of("content", Base64.getEncoder().encodeToString(SearchImagePreprocessor.shrink(image))),
                "features", List.of(Map.of("type", "WEB_DETECTION", "maxResults", MAX_RESULTS)))));

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/v1/images:annotate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            // 400/403 등은 다시 시도해도 같은 결과다. 429(쿼터)와 408은 일시적이라 일반 실패로 둔다.
            if (e.getStatusCode().value() != 429 && e.getStatusCode().value() != 408) {
                throw new NonRetryableSearchException("Vision 요청이 거절됐습니다: " + e.getStatusCode(), e);
            }
            throw e;
        }

        return parse(response);
    }

    static List<SearchHit> parse(JsonNode response) {
        JsonNode first = response == null ? null : response.path("responses").path(0);
        if (first == null || first.isMissingNode()) {
            throw new IllegalStateException("Vision 응답에 responses가 없습니다.");
        }
        if (first.hasNonNull("error")) {
            throw new IllegalStateException("Vision 오류: " + first.path("error").path("message").asText());
        }

        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode page : first.path("webDetection").path("pagesWithMatchingImages")) {
            String pageUrl = safeUrl(page.path("url").asText(null));
            if (pageUrl == null) {
                continue;
            }
            JsonNode full = page.path("fullMatchingImages").path(0);
            JsonNode partial = page.path("partialMatchingImages").path(0);
            String imageUrl = safeUrl(full.has("url") ? full.path("url").asText() : partial.path("url").asText(null));
            MatchType matchType = full.has("url") ? MatchType.FULL : partial.has("url") ? MatchType.PARTIAL : MatchType.PAGE;
            hits.add(new SearchHit(pageUrl, imageUrl, page.path("pageTitle").asText(null), matchType));
        }
        return hits;
    }

    /** 외부가 정한 값이므로 http/https만 받고, 컬럼 길이를 넘는 URL은 버린다. 쓸 수 없으면 null. */
    public static String safeUrl(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            return null;
        }
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
