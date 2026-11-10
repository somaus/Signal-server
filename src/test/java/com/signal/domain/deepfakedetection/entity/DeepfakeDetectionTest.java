package com.signal.domain.deepfakedetection.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeepfakeDetectionTest {

    private DeepfakeDetection detection() {
        return DeepfakeDetection.builder().userId(1L).fileUrl("f.jpg").build();
    }

    @Test
    void 새로_만들면_PROCESSING이고_목록이_비어_있다() {
        DeepfakeDetection d = detection();

        assertThat(d.getStatus()).isEqualTo(DeepfakeDetectionStatus.PROCESSING);
        assertThat(d.getEvidences()).isEmpty();
        assertThat(d.getProvenanceSignals()).isEmpty();
        assertThat(d.getFallbackUsed()).isNull();
    }

    @Test
    void complete하면_COMPLETED가_되고_결과가_남는다() {
        DeepfakeDetection d = detection();

        d.complete(Verdict.FAKE, 0.9, 85, List.of(), "h.jpg", true);

        assertThat(d.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(d.getVerdict()).isEqualTo(Verdict.FAKE);
        assertThat(d.getRiskScore()).isEqualTo(85);
        assertThat(d.getFallbackUsed()).isTrue();
        assertThat(d.getBand()).isNull();
    }

    @Test
    void completeAssessed는_등급과_신호를_이름으로_저장하고_폴백이_아니다() {
        DeepfakeDetection d = detection();

        d.completeAssessed(DetectionBand.HIGH, AssessmentBasis.PROVENANCE, null, null, null, null, List.of(), null,
                "model", "v1", List.of(ProvenanceSignal.GENERATOR_METADATA));

        assertThat(d.getStatus()).isEqualTo(DeepfakeDetectionStatus.COMPLETED);
        assertThat(d.getBand()).isEqualTo("HIGH");
        assertThat(d.getAssessmentBasis()).isEqualTo("PROVENANCE");
        assertThat(d.getUndeterminedReason()).isNull();
        assertThat(d.getRiskScore()).isNull();
        assertThat(d.getFallbackUsed()).isFalse();
        assertThat(d.getProvenanceSignals()).containsExactly("GENERATOR_METADATA");
    }

    @Test
    void completeAssessed에서_판단_불가_이유를_남긴다() {
        DeepfakeDetection d = detection();

        d.completeAssessed(DetectionBand.UNDETERMINED, null, UndeterminedReason.LOW_RESOLUTION, null, null, null,
                List.of(), null, "model", "v1", List.of());

        assertThat(d.getAssessmentBasis()).isNull();
        assertThat(d.getUndeterminedReason()).isEqualTo("LOW_RESOLUTION");
    }

    @Test
    void fail하면_FAILED와_사유가_남는다() {
        DeepfakeDetection d = detection();

        d.fail(DetectionFailureReason.TIMEOUT);

        assertThat(d.getStatus()).isEqualTo(DeepfakeDetectionStatus.FAILED);
        assertThat(d.getFailureReason()).isEqualTo(DetectionFailureReason.TIMEOUT);
    }

    @Test
    void 회원_소유면_userId로_비회원이면_anonymousId로_판단한다() {
        DeepfakeDetection member = detection();
        DeepfakeDetection guest = DeepfakeDetection.builder().anonymousId("anon").fileUrl("f.jpg").build();

        assertThat(member.isOwnedBy(1L, null)).isTrue();
        assertThat(member.isOwnedBy(null, null)).isFalse();
        assertThat(guest.isOwnedBy(null, "anon")).isTrue();
        assertThat(guest.isOwnedBy(null, "other")).isFalse();
    }

    @Test
    void complete에_불변_목록을_넘겨도_수정_가능한_복사본으로_보관한다() {
        DeepfakeDetection d = detection();
        List<Evidence> given = List.of(new Evidence("t", "d", null, null));

        d.complete(Verdict.FAKE, 0.9, 85, given, "h.jpg", false);
        d.getEvidences().add(new Evidence("t2", "d2", null, null));

        assertThat(d.getEvidences()).hasSize(2);
        assertThat(given).hasSize(1);
    }

    @Test
    void completeAssessed에_null_근거를_넘기면_빈_목록이_된다() {
        DeepfakeDetection d = detection();

        d.completeAssessed(DetectionBand.HIGH, AssessmentBasis.PROVENANCE, null, null, null, null, null, null,
                "model", "v1", List.of());

        assertThat(d.getEvidences()).isEmpty();
    }
}
