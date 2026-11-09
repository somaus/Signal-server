package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.i18n.ResponseLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RiskTextsTest {

    @Test
    void 알려진_type은_언어별_이름을_돌려준다() {
        assertThat(RiskTexts.label(RiskTexts.FACE_EXPOSURE, ResponseLanguage.KO, "x")).isEqualTo("얼굴 노출도");
        assertThat(RiskTexts.label(RiskTexts.FACE_EXPOSURE, ResponseLanguage.EN, "x")).isEqualTo("Face exposure");
        assertThat(RiskTexts.label(RiskTexts.METADATA_LEAK, ResponseLanguage.JA, "x")).isEqualTo("メタデータの露出");
        assertThat(RiskTexts.label(RiskTexts.IMAGE_QUALITY, ResponseLanguage.ZH_CN, "x")).isEqualTo("图像质量");
    }

    @Test
    void 모르는_type이나_null이면_fallback을_그대로_돌려준다() {
        assertThat(RiskTexts.label("UNKNOWN", ResponseLanguage.EN, "저장된 이름")).isEqualTo("저장된 이름");
        assertThat(RiskTexts.label(null, ResponseLanguage.EN, "저장된 이름")).isEqualTo("저장된 이름");
    }

    @Test
    void GPS_유무에_따라_메타데이터_설명이_달라진다() {
        assertThat(RiskTexts.metadataDescription(true, ResponseLanguage.EN)).contains("contains");
        assertThat(RiskTexts.metadataDescription(false, ResponseLanguage.EN)).startsWith("No location");
    }

    @Test
    void 점수는_언어별_라벨과_함께_붙는다() {
        assertThat(RiskTexts.withScore("메모", 80, ResponseLanguage.KO)).isEqualTo("메모 (점수: 80)");
        assertThat(RiskTexts.withScore("note", 5, ResponseLanguage.EN)).isEqualTo("note (score: 5)");
    }

    @ParameterizedTest
    @EnumSource(ResponseLanguage.class)
    void 모든_언어에서_등급별_권고_개수가_같다(ResponseLanguage language) {
        assertThat(RiskTexts.recommendations(RiskLevel.HIGH, language)).hasSize(3);
        assertThat(RiskTexts.recommendations(RiskLevel.MEDIUM, language)).hasSize(2);
        assertThat(RiskTexts.recommendations(RiskLevel.LOW, language)).hasSize(1);
    }
}
