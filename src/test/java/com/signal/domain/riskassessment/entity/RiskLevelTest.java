package com.signal.domain.riskassessment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskLevelTest {

    @ParameterizedTest
    @CsvSource({"100,HIGH", "70,HIGH", "69,MEDIUM", "40,MEDIUM", "39,LOW", "0,LOW", "-1,LOW"})
    void 점수_경계값에_따라_등급이_정해진다(int score, RiskLevel expected) {
        assertThat(RiskLevel.fromScore(score)).isEqualTo(expected);
    }
}
