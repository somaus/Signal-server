package com.signal.domain.riskassessment.analyzer;

/**
 * 웹 역검색에서 찾은 페이지 수를 0~100 점수로 바꾼다.
 *
 * 이 값은 "같은 사진이 웹에 이미 퍼져 있는 정도"를 나타내는 임의의 환산이다. 실제 피해 확률이나 검증된 위험
 * 모델이 아니다(docs/risk-analysis.md의 한계 참고).
 * - 같은 이미지가 있으면 70점에서 시작해 페이지가 하나 늘 때마다 10점씩 올라 100점에서 멈춘다.
 * - 같은 이미지는 없고 비슷한 이미지만 있으면 30점에서 시작해 5점씩 올라 50점에서 멈춘다.
 * - 아무것도 찾지 못하면 5점이다(검색되지 않는 곳에 있을 수 있으므로 0점으로 두지 않는다).
 */
public final class ReverseSearchScoring {

    static final int NOT_FOUND = 5;
    static final int SIMILAR_BASE = 30;
    static final int SIMILAR_MAX = 50;
    static final int SAME_BASE = 70;
    static final int STEP_SAME = 10;
    static final int STEP_SIMILAR = 5;

    private ReverseSearchScoring() {
    }

    public static int score(int sameImageCount, int similarImageCount) {
        if (sameImageCount > 0) {
            return Math.min(100, SAME_BASE + STEP_SAME * (sameImageCount - 1));
        }
        if (similarImageCount > 0) {
            return Math.min(SIMILAR_MAX, SIMILAR_BASE + STEP_SIMILAR * (similarImageCount - 1));
        }
        return NOT_FOUND;
    }
}
