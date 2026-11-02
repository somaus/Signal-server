package com.signal.domain.monitoring.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.monitoring.entity.MatchType;
import com.signal.domain.monitoring.monitor.ReverseImageSearcher.SearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoogleVisionReverseImageSearcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 전체_부분_일치_여부에_따라_유사도를_다르게_매긴다() throws Exception {
        String json = """
                {"responses":[{"webDetection":{"pagesWithMatchingImages":[
                  {"url":"https://a.com/1","pageTitle":"A","fullMatchingImages":[{"url":"https://a.com/1.jpg"}]},
                  {"url":"https://b.com/2","partialMatchingImages":[{"url":"https://b.com/2.jpg"}]},
                  {"url":"https://c.com/3"}
                ]}}]}""";

        List<SearchHit> hits = GoogleVisionReverseImageSearcher.parse(mapper.readTree(json));

        assertThat(hits).extracting(SearchHit::matchType).containsExactly(MatchType.FULL, MatchType.PARTIAL, MatchType.PAGE);
        assertThat(hits.get(0).imageUrl()).isEqualTo("https://a.com/1.jpg");
        assertThat(hits.get(0).title()).isEqualTo("A");
        assertThat(hits.get(2).imageUrl()).isNull();
    }

    @Test
    void 일치하는_페이지가_없으면_빈_목록이다() throws Exception {
        assertThat(GoogleVisionReverseImageSearcher.parse(mapper.readTree("{\"responses\":[{}]}"))).isEmpty();
    }

    @Test
    void url이_없는_항목은_건너뛴다() throws Exception {
        String json = "{\"responses\":[{\"webDetection\":{\"pagesWithMatchingImages\":[{\"pageTitle\":\"x\"}]}}]}";

        assertThat(GoogleVisionReverseImageSearcher.parse(mapper.readTree(json))).isEmpty();
    }

    @Test
    void http_https가_아닌_스킴과_너무_긴_URL은_버린다() throws Exception {
        String longUrl = "https://a.com/" + "x".repeat(2048);
        String json = "{\"responses\":[{\"webDetection\":{\"pagesWithMatchingImages\":["
                + "{\"url\":\"javascript:alert(1)\"},{\"url\":\"" + longUrl + "\"},"
                + "{\"url\":\"https://ok.com/p\",\"fullMatchingImages\":[{\"url\":\"data:image/png;base64,AAA\"}]}]}}]}";

        List<SearchHit> hits = GoogleVisionReverseImageSearcher.parse(mapper.readTree(json));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).pageUrl()).isEqualTo("https://ok.com/p");
        assertThat(hits.get(0).imageUrl()).isNull();
    }

    @Test
    void API_오류_응답은_예외로_올린다() throws Exception {
        String json = "{\"responses\":[{\"error\":{\"code\":8,\"message\":\"quota exceeded\"}}]}";

        assertThatThrownBy(() -> GoogleVisionReverseImageSearcher.parse(mapper.readTree(json)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("quota exceeded");
    }

    @Test
    void responses가_없으면_예외로_올린다() throws Exception {
        assertThatThrownBy(() -> GoogleVisionReverseImageSearcher.parse(mapper.readTree("{}")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_없으면_기동_시점에_실패한다() {
        assertThatThrownBy(() -> new GoogleVisionReverseImageSearcher(
                org.springframework.web.client.RestClient.builder(), " ", "http://localhost", 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GOOGLE_VISION_API_KEY");
    }
}
