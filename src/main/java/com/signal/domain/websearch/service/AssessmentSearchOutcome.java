package com.signal.domain.websearch.service;

import com.signal.domain.websearch.entity.UnavailableReason;
import com.signal.domain.websearch.entity.WebSearchStatus;

/**
 * 위험도 분석에서 한 웹 역검색의 결과. 찾은 페이지 주소는 남기지 않고 건수만 돌려준다.
 *
 * @param sameImageCount 같은 이미지(FULL·PARTIAL)로 찾은 페이지 수
 * @param similarImageCount 비슷한 이미지나 같은 페이지 노출(SIMILAR·PAGE)로 찾은 페이지 수
 * @param reason status가 UNAVAILABLE일 때의 이유
 */
public record AssessmentSearchOutcome(
        WebSearchStatus status,
        UnavailableReason reason,
        int sameImageCount,
        int similarImageCount
) {

    public static AssessmentSearchOutcome completed(int sameImageCount, int similarImageCount) {
        return new AssessmentSearchOutcome(WebSearchStatus.COMPLETED, null, sameImageCount, similarImageCount);
    }

    public static AssessmentSearchOutcome unavailable(UnavailableReason reason) {
        return new AssessmentSearchOutcome(WebSearchStatus.UNAVAILABLE, reason, 0, 0);
    }

    public static AssessmentSearchOutcome failed() {
        return new AssessmentSearchOutcome(WebSearchStatus.FAILED, null, 0, 0);
    }

    public boolean isCompleted() {
        return status == WebSearchStatus.COMPLETED;
    }
}
