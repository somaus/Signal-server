package com.signal.domain.chat.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.signal.domain.chat.engine.SituationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsultationProgressTrackerTest {

    private final StageAnalyzer stageAnalyzer = mock(StageAnalyzer.class);
    private final ConsultationProgressTracker tracker = new ConsultationProgressTracker(stageAnalyzer);

    private static StageFacts facts(boolean crisis) {
        return new StageFacts(crisis, crisis, false, false, false, 1);
    }

    @Test
    void 한번_이미지_피해로_분류되면_이후_일반_메시지에도_유형이_유지된다() {
        given(stageAnalyzer.analyze(any(), any(), any())).willReturn(Map.of());
        StageSnapshot previous = new StageSnapshot(SituationType.IMAGE_ABUSE, Map.of());

        StageSnapshot updated = tracker.update(previous, SituationType.GENERAL, List.of(), facts(false));

        assertThat(updated.type()).isEqualTo(SituationType.IMAGE_ABUSE);
        verify(stageAnalyzer).analyze(eq(SituationType.IMAGE_ABUSE), any(), any());
    }

    @Test
    void 현재_유형이_null이면_일반_상담이다() {
        given(stageAnalyzer.analyze(any(), any(), any())).willReturn(Map.of());

        assertThat(tracker.update(null, null, List.of(), facts(false)).type()).isEqualTo(SituationType.GENERAL);
    }

    @Test
    void 위기_상황에서는_LLM에_대화를_보내지_않는다() {
        StageSnapshot updated = tracker.update(null, SituationType.GENERAL, List.of(), facts(true));

        verifyNoInteractions(stageAnalyzer);
        assertThat(updated.stages()).isNotEmpty();
    }

    @Test
    void LLM_분석이_실패해도_룰_기반_상태로_갱신한다() {
        given(stageAnalyzer.analyze(any(), any(), any())).willThrow(new RuntimeException("timeout"));

        StageSnapshot updated = tracker.update(null, SituationType.IMAGE_ABUSE, List.of(), facts(false));

        assertThat(updated.type()).isEqualTo(SituationType.IMAGE_ABUSE);
        assertThat(updated.stages()).isNotEmpty();
    }

    @Test
    void LLM은_단계를_완료로_만들_수_없다() {
        given(stageAnalyzer.analyze(any(), any(), any()))
                .willReturn(Map.of(ConsultationStage.AGENCY_GUIDANCE, StageStatus.COMPLETED));

        StageSnapshot updated = tracker.update(null, SituationType.GENERAL, List.of(), facts(false));

        assertThat(updated.stages().get(ConsultationStage.AGENCY_GUIDANCE)).isNotEqualTo(StageStatus.COMPLETED);
    }
}
