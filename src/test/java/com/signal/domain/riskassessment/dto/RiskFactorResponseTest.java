package com.signal.domain.riskassessment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.riskassessment.analyzer.RiskTexts;
import com.signal.domain.riskassessment.dto.response.RiskFactorResponse;
import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RiskFactorResponseTest {

    @AfterEach
    void tearDown() {
        LanguageContext.clear();
    }

    private RiskFactor factor(String type, String description) {
        return RiskFactor.builder().type(type).label("저장된 이름").score(40).description(description).build();
    }

    @Test
    void 역검색_항목_설명_앞에_요청_언어의_추정치_안내를_붙인다() {
        LanguageContext.set(ResponseLanguage.EN);

        RiskFactorResponse response = RiskFactorResponse.from(factor("REVERSE_SEARCH_RISK", "저장된 설명"));

        assertThat(response.description())
                .isEqualTo(RiskTexts.reverseSearchDisclaimer(ResponseLanguage.EN) + " 저장된 설명");
        assertThat(response.label()).isEqualTo("Exposure likelihood (estimate)");
    }

    @Test
    void 안내는_저장된_값이_아니라_응답을_만들_때_붙으므로_요청_언어가_바뀌면_안내_언어도_바뀐다() {
        RiskFactor stored = factor("REVERSE_SEARCH_RISK", "저장된 설명");

        LanguageContext.set(ResponseLanguage.KO);
        String korean = RiskFactorResponse.from(stored).description();
        LanguageContext.set(ResponseLanguage.JA);
        String japanese = RiskFactorResponse.from(stored).description();

        assertThat(korean).startsWith(RiskTexts.reverseSearchDisclaimer(ResponseLanguage.KO));
        assertThat(japanese).startsWith(RiskTexts.reverseSearchDisclaimer(ResponseLanguage.JA));
        assertThat(stored.getDescription()).isEqualTo("저장된 설명");
    }

    @Test
    void 다른_항목의_설명은_그대로_둔다() {
        LanguageContext.set(ResponseLanguage.KO);

        RiskFactorResponse response = RiskFactorResponse.from(factor("IMAGE_QUALITY", "저장된 설명"));

        assertThat(response.description()).isEqualTo("저장된 설명");
    }
}
