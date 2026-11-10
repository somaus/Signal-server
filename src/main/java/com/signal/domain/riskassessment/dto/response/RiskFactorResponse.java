package com.signal.domain.riskassessment.dto.response;

import com.signal.domain.riskassessment.analyzer.RiskTexts;
import com.signal.domain.riskassessment.entity.RiskFactor;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;

public record RiskFactorResponse(String type, String label, int score, String description) {

    /**
     * label은 저장된 이름이 아니라 type과 요청 언어(Accept-Language)로 정해 이전에 저장된 진단도 현재 언어로 보인다.
     * description은 AI가 쓴 문장이라 저장 당시 언어로 내려간다.
     * 역검색 항목은 실제 검색 결과가 아니라 추정치이므로, 저장된 기록·폴백 결과도 같게 보이도록 요청 언어의 안내 문구를
     * 응답을 만들 때 앞에 붙인다(저장하지 않는다).
     */
    public static RiskFactorResponse from(RiskFactor riskFactor) {
        ResponseLanguage language = LanguageContext.current();
        String label = RiskTexts.label(riskFactor.getType(), language, riskFactor.getLabel());
        String description = RiskTexts.REVERSE_SEARCH_RISK.equals(riskFactor.getType())
                ? RiskTexts.reverseSearchDisclaimer(language) + " " + riskFactor.getDescription()
                : riskFactor.getDescription();
        return new RiskFactorResponse(riskFactor.getType(), label, riskFactor.getScore(), description);
    }
}
