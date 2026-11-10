package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReverseSearchScoringTest {

    @Test
    void 아무것도_찾지_못하면_0이_아니라_5점이다() {
        assertThat(ReverseSearchScoring.score(0, 0)).isEqualTo(5);
    }

    @Test
    void 비슷한_이미지만_있으면_30점에서_시작해_50점에서_멈춘다() {
        assertThat(ReverseSearchScoring.score(0, 1)).isEqualTo(30);
        assertThat(ReverseSearchScoring.score(0, 3)).isEqualTo(40);
        assertThat(ReverseSearchScoring.score(0, 50)).isEqualTo(50);
    }

    @Test
    void 같은_이미지가_있으면_70점에서_시작해_100점에서_멈춘다() {
        assertThat(ReverseSearchScoring.score(1, 0)).isEqualTo(70);
        assertThat(ReverseSearchScoring.score(2, 0)).isEqualTo(80);
        assertThat(ReverseSearchScoring.score(4, 0)).isEqualTo(100);
        assertThat(ReverseSearchScoring.score(40, 0)).isEqualTo(100);
    }

    @Test
    void 같은_이미지가_있으면_비슷한_이미지_수는_점수에_영향이_없다() {
        assertThat(ReverseSearchScoring.score(1, 30)).isEqualTo(ReverseSearchScoring.score(1, 0));
    }
}
