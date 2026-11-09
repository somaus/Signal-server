package com.signal.global.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LanguageContextTest {

    @AfterEach
    void tearDown() {
        LanguageContext.clear();
    }

    @Test
    void 값이_없으면_한국어다() {
        assertThat(LanguageContext.current()).isEqualTo(ResponseLanguage.KO);
    }

    @Test
    void 설정한_언어를_돌려주고_clear하면_한국어로_돌아간다() {
        LanguageContext.set(ResponseLanguage.JA);
        assertThat(LanguageContext.current()).isEqualTo(ResponseLanguage.JA);

        LanguageContext.clear();
        assertThat(LanguageContext.current()).isEqualTo(ResponseLanguage.KO);
    }

    @Test
    void 다른_스레드에는_전파되지_않는다() throws Exception {
        LanguageContext.set(ResponseLanguage.EN);

        ResponseLanguage other = CompletableFuture.supplyAsync(LanguageContext::current).get();

        assertThat(other).isEqualTo(ResponseLanguage.KO);
    }
}
