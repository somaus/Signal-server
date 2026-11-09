package com.signal.domain.report.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.domain.chat.engine.ChatCompletionClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmReportSummarizerTest {

    private static final String LONG_TEXT = "지난달 초에 인스타그램 DM으로 제 얼굴이 합성된 사진이 도착했습니다. 그 사진은 제가 올린 적이 없는 사진이었고 "
            + "계정 이름은 abc_123 이었습니다. 다음 날 같은 사진이 트위터에도 올라온 것을 지인이 알려 주었습니다.";

    private final ChatCompletionClient client = mock(ChatCompletionClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmReportSummarizer summarizer(boolean enabled) {
        return new LlmReportSummarizer(client, objectMapper, enabled);
    }

    @Test
    void LLM이_준_요약을_돌려준다() {
        when(client.completeJson(anyString(), anyString()))
                .thenReturn("{\"summary\": \"신고인은 인스타그램 DM으로 합성 사진을 받았다.\"}");

        assertThat(summarizer(true).summarize(LONG_TEXT, "얼굴 합성"))
                .contains("신고인은 인스타그램 DM으로 합성 사진을 받았다.");
    }

    @Test
    void 요약_요청에는_원문과_피해_유형이_들어가고_지어내기_금지_지시가_있다() {
        when(client.completeJson(anyString(), anyString())).thenReturn("{\"summary\": \"요약\"}");

        summarizer(true).summarize(LONG_TEXT, "얼굴 합성");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(client).completeJson(system.capture(), user.capture());
        assertThat(system.getValue()).contains("원문에 있는 사실만").contains("법적 판단");
        assertThat(user.getValue()).contains("얼굴 합성").contains("abc_123");
    }

    @Test
    void 코드블록으로_감싼_JSON도_읽는다() {
        when(client.completeJson(anyString(), anyString()))
                .thenReturn("```json\n{\"summary\": \"요약문\"}\n```");

        assertThat(summarizer(true).summarize(LONG_TEXT, null)).contains("요약문");
    }

    @Test
    void 너무_긴_요약은_상한에서_자른다() {
        String huge = "가".repeat(LlmReportSummarizer.MAX_SUMMARY_LENGTH + 500);
        when(client.completeJson(anyString(), anyString())).thenReturn("{\"summary\": \"" + huge + "\"}");

        assertThat(summarizer(true).summarize(LONG_TEXT, null).orElseThrow())
                .hasSize(LlmReportSummarizer.MAX_SUMMARY_LENGTH);
    }

    @Test
    void 원문이_짧으면_LLM을_부르지_않고_요약하지_않는다() {
        assertThat(summarizer(true).summarize("짧은 글입니다.", "얼굴 합성")).isEmpty();

        verifyNoInteractions(client);
    }

    @Test
    void 비활성화되어_있으면_요약하지_않는다() {
        assertThat(summarizer(false).summarize(LONG_TEXT, null)).isEmpty();

        verifyNoInteractions(client);
    }

    @Test
    void LLM_호출이_실패하면_요약_없이_진행한다() {
        when(client.completeJson(anyString(), anyString())).thenThrow(new IllegalStateException("타임아웃"));

        assertThat(summarizer(true).summarize(LONG_TEXT, null)).isEmpty();
    }

    @Test
    void 응답이_JSON이_아니거나_요약이_비어_있으면_요약하지_않는다() {
        when(client.completeJson(anyString(), anyString()))
                .thenReturn("죄송합니다")
                .thenReturn("{\"summary\": \"  \"}")
                .thenReturn("{\"other\": 1}");

        assertThat(summarizer(true).summarize(LONG_TEXT, null)).isEmpty();
        assertThat(summarizer(true).summarize(LONG_TEXT, null)).isEmpty();
        assertThat(summarizer(true).summarize(LONG_TEXT, null)).isEmpty();
    }

    @Test
    void 길이_제한으로_자를_때_이모지의_한가운데서_자르지_않는다() {
        String text = "가".repeat(9) + "😀" + "나나나";   // 😀는 서로게이트 쌍으로 인덱스 9, 10

        String truncated = LlmReportSummarizer.truncate(text, 10);

        assertThat(truncated).isEqualTo("가".repeat(9));
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    @Test
    void 길이_제한_안이면_그대로_둔다() {
        assertThat(LlmReportSummarizer.truncate("짧은 요약", 100)).isEqualTo("짧은 요약");
    }
}
