package com.signal.domain.chat.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.chat.engine.SituationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConsultationStageTest {

    @Test
    void 일반_상담은_증거_보존과_플랫폼_신고_단계가_빠진다() {
        assertThat(ConsultationStage.applicableTo(SituationType.GENERAL)).containsExactly(
                ConsultationStage.SITUATION_CHECK,
                ConsultationStage.EMOTIONAL_SUPPORT,
                ConsultationStage.AGENCY_GUIDANCE);
    }

    @Test
    void 이미지_피해_상담은_모든_단계를_순서대로_쓴다() {
        assertThat(ConsultationStage.applicableTo(SituationType.IMAGE_ABUSE))
                .containsExactly(ConsultationStage.values());
    }

    @Test
    void 유형이_null이면_일반_상담으로_취급한다() {
        assertThat(ConsultationStage.EVIDENCE_PRESERVATION.isApplicableTo(null)).isFalse();
        assertThat(ConsultationStage.SITUATION_CHECK.isApplicableTo(null)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(SituationType.class)
    void 모든_상담_유형에는_적용되는_단계가_하나_이상_있다(SituationType type) {
        assertThat(ConsultationStage.applicableTo(type)).isNotEmpty();
    }
}
