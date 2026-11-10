package com.signal.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.chat.engine.SituationType;
import org.junit.jupiter.api.Test;

class ChatSessionTest {

    private ChatSession session() {
        return ChatSession.builder().sessionId("s1").userId(1L).saveConsent(true).build();
    }

    @Test
    void 첫_메시지만_미리보기로_남고_100자로_자른다() {
        ChatSession s = session();

        s.recordUserMessage("가".repeat(150));
        s.recordUserMessage("두번째");

        assertThat(s.getFirstMessagePreview()).isEqualTo("가".repeat(100));
    }

    @Test
    void 짧은_첫_메시지는_그대로_미리보기가_된다() {
        ChatSession s = session();

        s.recordUserMessage("안녕");

        assertThat(s.getFirstMessagePreview()).isEqualTo("안녕");
    }

    @Test
    void 위기가_한_번이라도_감지되면_everCrisisDetected는_계속_참이다() {
        ChatSession s = session();

        s.recordEngineResult(SituationType.IMAGE_ABUSE, true, false);
        s.recordEngineResult(SituationType.GENERAL, false, false);

        assertThat(s.isLastCrisisDetected()).isFalse();
        assertThat(s.isEverCrisisDetected()).isTrue();
        assertThat(s.getLastSituationType()).isEqualTo(SituationType.GENERAL);
    }

    @Test
    void 기관_안내는_한_번_주면_계속_참이다() {
        ChatSession s = session();

        s.recordEngineResult(SituationType.GENERAL, false, true);
        s.recordEngineResult(SituationType.GENERAL, false, false);

        assertThat(s.isAgenciesRecommended()).isTrue();
    }

    @Test
    void 종료_확인을_거절하면_대기만_풀리고_세션은_계속된다() {
        ChatSession s = session();
        s.markAwaitingEndConfirmation();

        s.resolveEndConfirmation(false);

        assertThat(s.isAwaitingEndConfirmation()).isFalse();
        assertThat(s.isSessionEnded()).isFalse();
    }

    @Test
    void 종료_확인을_수락하면_세션이_끝난다() {
        ChatSession s = session();
        s.markAwaitingEndConfirmation();

        s.resolveEndConfirmation(true);

        assertThat(s.isAwaitingEndConfirmation()).isFalse();
        assertThat(s.isSessionEnded()).isTrue();
    }

    @Test
    void 회원_소유면_userId로_비회원이면_anonymousId로_판단한다() {
        ChatSession guest = ChatSession.builder().sessionId("s2").anonymousId("anon").build();

        assertThat(session().isOwnedBy(1L, null)).isTrue();
        assertThat(session().isOwnedBy(2L, null)).isFalse();
        assertThat(guest.isOwnedBy(null, "anon")).isTrue();
        assertThat(guest.isOwnedBy(null, null)).isFalse();
    }
}
