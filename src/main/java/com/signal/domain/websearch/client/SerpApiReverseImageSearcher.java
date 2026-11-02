package com.signal.domain.websearch.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.signal.domain.monitoring.entity.MatchType;
import com.signal.domain.monitoring.monitor.GoogleVisionReverseImageSearcher;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher;
import com.signal.domain.monitoring.monitor.SearchImagePreprocessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * SerpApi의 Google Lens로 같은 이미지가 실린 페이지를 찾는다(무료 한도 월 100~250건, 카드 불필요).
 * Lens는 이미지를 공개 URL로만 받으므로 검색하는 동안만 {@link TempImageStore}로 이미지를 열어 둔다.
 * 얼굴을 비교하지 않고 이미지 단위로 찾으므로 같은 사진이 아닌 "같은 사람의 다른 사진"은 찾지 못한다.
 */
@Component
@Qualifier("serpApi")
@ConditionalOnProperty(name = "web-search.provider", havingValue = "serpapi")
public class SerpApiReverseImageSearcher implements ReverseImageSearcher {

    static final int MAX_RESULTS = 20;
    static final String TEMP_IMAGE_PATH = "/api/v1/web-search/temp-images/";

    private final RestClient restClient;
    private final String apiKey;
    private final String publicBaseUrl;
    private final TempImageStore tempImageStore;

    public SerpApiReverseImageSearcher(RestClient.Builder builder,
                                       TempImageStore tempImageStore,
                                       @Value("${web-search.serpapi.api-key:}") String apiKey,
                                       @Value("${web-search.serpapi.base-url:https://serpapi.com}") String baseUrl,
                                       @Value("${web-search.public-base-url:}") String publicBaseUrl,
                                       @Value("${web-search.timeout-seconds:60}") long timeoutSeconds) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "web-search.provider=serpapi 에는 SERPAPI_API_KEY가 필요합니다. 키가 없으면 WEB_SEARCH_PROVIDER=none 으로 두세요.");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "SerpApi는 이미지를 공개 URL로 받습니다. 서버의 외부 주소를 WEB_SEARCH_PUBLIC_BASE_URL로 지정하세요(예: https://api.example.com).");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.tempImageStore = tempImageStore;
    }

    @Override
    public List<SearchHit> search(byte[] image) {
        byte[] jpeg = SearchImagePreprocessor.shrink(image);
        String token = tempImageStore.put(jpeg, "image/jpeg");
        try {
            String imageUrl = publicBaseUrl + TEMP_IMAGE_PATH + token;
            JsonNode response = fetch(imageUrl);
            return parse(response);
        } finally {
            tempImageStore.remove(token);
        }
    }

    /**
     * SerpApi는 키를 쿼리 파라미터로만 받는다. RestClient의 I/O 오류 메시지에는 요청 URL 전체(api_key, 임시 이미지 주소)가
     * 들어가므로 원인 예외를 그대로 던지지 않고, 키가 없는 메시지로 바꿔 던진다.
     */
    private JsonNode fetch(String imageUrl) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/search.json")
                            .queryParam("engine", "google_lens")
                            // 도용 탐지에는 같은 이미지(exact_matches)가 맞다. 기본 호출은 비슷한 이미지(visual_matches)만 준다.
                            .queryParam("type", "exact_matches")
                            .queryParam("url", imageUrl)
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("SerpApi 호출 실패: HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new IllegalStateException("SerpApi 호출 실패: " + e.getClass().getSimpleName());
        }
    }

    static List<SearchHit> parse(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("SerpApi 응답이 비어 있습니다.");
        }
        if (response.hasNonNull("error")) {
            String error = response.path("error").asText();
            // 결과가 없는 것은 실패가 아니다. SerpApi는 이를 error 문구로만 알려 주므로 이 문구에 의존한다(바뀌면 FAILED로 집계된다).
            if (error.contains("hasn't returned any results")) {
                return List.of();
            }
            throw new IllegalStateException("SerpApi 오류: " + error);
        }

        List<SearchHit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(response.path("exact_matches"), MatchType.FULL, hits, seen);
        collect(response.path("visual_matches"), MatchType.SIMILAR, hits, seen);
        return hits;
    }

    private static void collect(JsonNode items, MatchType matchType, List<SearchHit> hits, Set<String> seen) {
        for (JsonNode item : items) {
            if (hits.size() >= MAX_RESULTS) {
                return;
            }
            String link = GoogleVisionReverseImageSearcher.safeUrl(item.path("link").asText(null));
            if (link == null || !seen.add(link)) {
                continue;
            }
            hits.add(new SearchHit(link, null, item.path("title").asText(null), matchType));
        }
    }
}
