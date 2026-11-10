package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.i18n.ResponseLanguage;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskResultAdjusterTest {

    private RiskFactor factor(String type, int score) {
        return RiskFactor.builder().type(type).label(type).score(score).description("설명").build();
    }

    private RiskAnalysisResult llmResult() {
        List<RiskFactor> factors = List.of(
                factor(RiskTexts.IMAGE_QUALITY, 80),
                factor(RiskTexts.FACE_EXPOSURE, 60),
                factor(RiskTexts.METADATA_LEAK, 10),
                factor(RiskTexts.REVERSE_SEARCH_RISK, 30));
        return new RiskAnalysisResult(true, RiskLevel.MEDIUM, 45, factors, List.of("권고"), List.of(), false);
    }

    private RiskAnalysisResult httpResult() {
        List<RiskFactor> factors = List.of(
                factor(RiskTexts.IMAGE_QUALITY, 80),
                factor(RiskTexts.FACE_EXPOSURE, 60),
                factor(RiskTexts.METADATA_LEAK, 10));
        return new RiskAnalysisResult(true, RiskLevel.MEDIUM, 58, factors, List.of("권고"), List.of(), false);
    }

    @Test
    void 추정_항목이_있으면_그_자리를_검색_결과로_바꾸고_평균을_다시_낸다() {
        RiskAnalysisResult adjusted = RiskResultAdjuster.apply(llmResult(), 2, 0, ResponseLanguage.KO);

        assertThat(adjusted.factors()).extracting(RiskFactor::getType).containsExactly(
                RiskTexts.IMAGE_QUALITY, RiskTexts.FACE_EXPOSURE, RiskTexts.METADATA_LEAK,
                RiskTexts.REVERSE_SEARCH_RESULT);
        assertThat(adjusted.factors().get(3).getScore()).isEqualTo(80);
        // (80 + 60 + 10 + 80) / 4 = 57.5 -> 58
        assertThat(adjusted.overallScore()).isEqualTo(58);
        assertThat(adjusted.overallRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void 같은_이미지가_퍼져_있으면_종합_등급이_올라간다() {
        RiskAnalysisResult adjusted = RiskResultAdjuster.apply(llmResult(), 4, 0, ResponseLanguage.KO);

        // (80 + 60 + 10 + 100) / 4 = 62.5 -> 63
        assertThat(adjusted.overallScore()).isEqualTo(63);
        assertThat(adjusted.recommendations()).isEqualTo(RiskTexts.recommendations(adjusted.overallRiskLevel(),
                ResponseLanguage.KO));
    }

    @Test
    void 아무것도_못_찾으면_추정치보다_점수가_내려갈_수_있다() {
        RiskAnalysisResult adjusted = RiskResultAdjuster.apply(llmResult(), 0, 0, ResponseLanguage.KO);

        assertThat(adjusted.factors().get(3).getScore()).isEqualTo(5);
        // (80 + 60 + 10 + 5) / 4 = 38.75 -> 39
        assertThat(adjusted.overallScore()).isEqualTo(39);
        assertThat(adjusted.overallRiskLevel()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void 추정_항목이_없으면_검색_결과를_덧붙이고_가중치를_나눠_다시_낸다() {
        RiskAnalysisResult adjusted = RiskResultAdjuster.apply(httpResult(), 1, 0, ResponseLanguage.KO);

        assertThat(adjusted.factors()).extracting(RiskFactor::getType).containsExactly(
                RiskTexts.IMAGE_QUALITY, RiskTexts.FACE_EXPOSURE, RiskTexts.METADATA_LEAK,
                RiskTexts.REVERSE_SEARCH_RESULT);
        // 0.30*80 + 0.30*60 + 0.15*10 + 0.25*70 = 24 + 18 + 1.5 + 17.5 = 61
        assertThat(adjusted.overallScore()).isEqualTo(61);
        assertThat(adjusted.overallRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void 검색_결과_항목의_이름과_설명은_요청_언어로_나온다() {
        RiskAnalysisResult adjusted = RiskResultAdjuster.apply(llmResult(), 2, 1, ResponseLanguage.EN);

        RiskFactor result = adjusted.factors().get(3);
        assertThat(result.getLabel()).isEqualTo("Web reverse-search result");
        assertThat(result.getDescription()).contains("2 web page(s) with the same image", "1 with a similar image");
    }

    @Test
    void 얼굴과_폴백_여부는_그대로_둔다() {
        RiskAnalysisResult original = llmResult();

        RiskAnalysisResult adjusted = RiskResultAdjuster.apply(original, 1, 1, ResponseLanguage.KO);

        assertThat(adjusted.faceDetected()).isTrue();
        assertThat(adjusted.fallbackUsed()).isFalse();
        assertThat(adjusted.faces()).isEqualTo(original.faces());
    }
}
