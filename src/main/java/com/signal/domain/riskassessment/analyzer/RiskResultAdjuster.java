package com.signal.domain.riskassessment.analyzer;

import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.i18n.ResponseLanguage;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 동의해 실제 웹 역검색을 한 경우, 분석 결과의 역검색 항목을 검색 결과로 바꾸고 종합 점수를 다시 낸다(#55).
 *
 * - 추정 항목(REVERSE_SEARCH_RISK)이 있는 결과(`llm` 방식): 그 항목을 검색 결과 항목으로 바꾸고 모든 항목의 평균을 다시 낸다.
 * - 추정 항목이 없는 결과(`http` 방식): 검색 결과 항목을 덧붙이고 {@link RiskScoring#overallWithReverseSearch}로 다시 낸다.
 * 어느 쪽이든 종합 등급과 권고 문구는 새 점수로 다시 정한다.
 */
public final class RiskResultAdjuster {

    private RiskResultAdjuster() {
    }

    public static RiskAnalysisResult apply(RiskAnalysisResult result, int sameImageCount, int similarImageCount,
                                           ResponseLanguage language) {
        int searchScore = ReverseSearchScoring.score(sameImageCount, similarImageCount);
        RiskFactor searchFactor = RiskFactor.builder()
                .type(RiskTexts.REVERSE_SEARCH_RESULT)
                .label(RiskTexts.label(RiskTexts.REVERSE_SEARCH_RESULT, language, "웹 역검색 결과"))
                .score(searchScore)
                .description(RiskTexts.reverseSearchResultDescription(
                        sameImageCount, similarImageCount, searchScore, language))
                .build();

        List<RiskFactor> factors = new ArrayList<>(result.factors());
        int estimateIndex = indexOfType(factors, RiskTexts.REVERSE_SEARCH_RISK);
        int overallScore;
        if (estimateIndex >= 0) {
            factors.set(estimateIndex, searchFactor);
            overallScore = (int) Math.round(factors.stream().mapToInt(RiskFactor::getScore).average().orElse(0));
        } else {
            factors.add(searchFactor);
            overallScore = RiskScoring.overallWithReverseSearch(
                    scoreOf(factors, RiskTexts.IMAGE_QUALITY),
                    scoreOf(factors, RiskTexts.FACE_EXPOSURE),
                    scoreOf(factors, RiskTexts.METADATA_LEAK),
                    searchScore);
        }

        RiskLevel level = RiskLevel.fromScore(overallScore);
        return new RiskAnalysisResult(result.faceDetected(), level, overallScore, factors,
                RiskTexts.recommendations(level, language), result.faces(), result.fallbackUsed());
    }

    private static int indexOfType(List<RiskFactor> factors, String type) {
        for (int i = 0; i < factors.size(); i++) {
            if (type.equals(factors.get(i).getType())) {
                return i;
            }
        }
        return -1;
    }

    private static int scoreOf(List<RiskFactor> factors, String type) {
        int index = indexOfType(factors, type);
        return index >= 0 ? factors.get(index).getScore() : 0;
    }
}
