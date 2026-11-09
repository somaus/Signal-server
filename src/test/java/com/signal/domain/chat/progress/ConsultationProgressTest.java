package com.signal.domain.chat.progress;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.chat.engine.SituationType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsultationProgressTest {

    @Test
    void 스냅샷이_없으면_시작_전이다() {
        ConsultationProgress progress = ConsultationProgress.from(null);

        assertThat(progress.status()).isEqualTo(ConsultationProgressStatus.NOT_STARTED);
        assertThat(progress.currentStage()).isNull();
        assertThat(progress.progressPercent()).isZero();
        assertThat(progress.stages()).containsOnlyKeys(ConsultationStage.values())
                .allSatisfy((stage, status) -> assertThat(status).isEqualTo(StageStatus.NOT_STARTED));
    }

    @Test
    void 일반_상담은_해당_없는_단계를_분모에서_빼고_진행률을_반올림한다() {
        ConsultationProgress progress = ConsultationProgress.from(new StageSnapshot(SituationType.GENERAL, Map.of(
                ConsultationStage.SITUATION_CHECK, StageStatus.COMPLETED,
                ConsultationStage.EVIDENCE_PRESERVATION, StageStatus.COMPLETED)));

        assertThat(progress.stages().get(ConsultationStage.EVIDENCE_PRESERVATION))
                .isEqualTo(StageStatus.NOT_APPLICABLE);
        assertThat(progress.completedStages()).containsExactly(ConsultationStage.SITUATION_CHECK);
        assertThat(progress.progressPercent()).isEqualTo(33);
        assertThat(progress.status()).isEqualTo(ConsultationProgressStatus.IN_PROGRESS);
        assertThat(progress.currentStage()).isEqualTo(ConsultationStage.EMOTIONAL_SUPPORT);
    }

    @Test
    void 진행_중인_단계가_있으면_그_단계가_현재_단계다() {
        ConsultationProgress progress = ConsultationProgress.from(new StageSnapshot(SituationType.IMAGE_ABUSE, Map.of(
                ConsultationStage.PLATFORM_REPORT, StageStatus.IN_PROGRESS)));

        assertThat(progress.currentStage()).isEqualTo(ConsultationStage.PLATFORM_REPORT);
        assertThat(progress.progressPercent()).isZero();
    }

    @Test
    void 적용되는_단계를_모두_마치면_완료이고_현재_단계는_없다() {
        ConsultationProgress progress = ConsultationProgress.from(new StageSnapshot(SituationType.GENERAL, Map.of(
                ConsultationStage.SITUATION_CHECK, StageStatus.COMPLETED,
                ConsultationStage.EMOTIONAL_SUPPORT, StageStatus.COMPLETED,
                ConsultationStage.AGENCY_GUIDANCE, StageStatus.COMPLETED)));

        assertThat(progress.status()).isEqualTo(ConsultationProgressStatus.COMPLETED);
        assertThat(progress.currentStage()).isNull();
        assertThat(progress.progressPercent()).isEqualTo(100);
    }
}
