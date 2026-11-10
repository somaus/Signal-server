package com.signal.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GroqVisionClientReplyTest {

    private static final String BASE_URL = "https://api.groq.com/openai/v1";

    private String replyTo(String responseJson) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GroqVisionClient client = new GroqVisionClient(
                builder.baseUrl(BASE_URL).build(), "test-key", "qwen/qwen3.8-27b", 1024);
        server.expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));
        return client.complete(new byte[] {1, 2, 3, 4}, "image/png", "system", "user");
    }

    @Test
    void choices가_없거나_비어_있으면_예외를_던진다() {
        assertThatThrownBy(() -> replyTo("{}")).isInstanceOf(IllegalStateException.class).hasMessageContaining("choices");
        assertThatThrownBy(() -> replyTo("{\"choices\":[]}")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choices");
    }

    @Test
    void message가_없거나_content가_비어_있으면_예외를_던진다() {
        assertThatThrownBy(() -> replyTo("{\"choices\":[{}]}")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content");
        assertThatThrownBy(() -> replyTo("{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("content");
        assertThatThrownBy(() -> replyTo("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"   \"}}]}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("content");
    }

    @Test
    void finish_reason이_stop이면_정상_응답이고_앞뒤_공백은_자른다() {
        assertThat(replyTo("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  hi \"},"
                + "\"finish_reason\":\"stop\"}]}")).isEqualTo("hi");
    }

    @Test
    void 여러_choices_중_첫_번째만_쓴다() {
        assertThat(replyTo("{\"choices\":[{\"message\":{\"content\":\"first\"}},{\"message\":{\"content\":\"second\"}}]}"))
                .isEqualTo("first");
    }
}
