package com.signal.domain.deepfakedetection.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.deepfakedetection.dto.response.DeepfakeDetectionResponse;
import com.signal.domain.deepfakedetection.entity.AssessmentBasis;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DetectionBand;
import com.signal.domain.deepfakedetection.entity.ProvenanceSignal;
import com.signal.domain.deepfakedetection.entity.UndeterminedReason;
import com.signal.domain.deepfakedetection.entity.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepfakeDetectionResponseTest {

    private DeepfakeDetection newDetection() {
        return DeepfakeDetection.builder().userId(1L).fileUrl("/uploads/a.png").build();
    }

    @Test
    void 등급과_모델_정보와_출처_신호가_응답에_담긴다() {
        DeepfakeDetection detection = newDetection();
        detection.completeAssessed(DetectionBand.HIGH, AssessmentBasis.PROVENANCE, null, Verdict.FAKE, null, null,
                List.of(), "/uploads/h.png", "cf", "onnx-fp32+abc",
                List.of(ProvenanceSignal.GENERATOR_METADATA, ProvenanceSignal.C2PA_MANIFEST));

        DeepfakeDetectionResponse response = DeepfakeDetectionResponse.from(detection);

        assertThat(response.band()).isEqualTo("HIGH");
        assertThat(response.assessmentBasis()).isEqualTo("PROVENANCE");
        assertThat(response.modelName()).isEqualTo("cf");
        assertThat(response.modelVersion()).isEqualTo("onnx-fp32+abc");
        assertThat(response.riskScore()).isNull();
        assertThat(response.confidence()).isNull();
        assertThat(response.provenanceSignals()).hasSize(2);
        assertThat(response.provenanceSignals().get(0).code()).isEqualTo("GENERATOR_METADATA");
        assertThat(response.provenanceSignals().get(0).strong()).isTrue();
        assertThat(response.provenanceSignals().get(1).strong()).isFalse();
        assertThat(response.provenanceSignals().get(1).message()).contains("서명은 검증하지 않았습니다");
    }

    @Test
    void 판단_불가는_이유가_담기고_verdict가_없다() {
        DeepfakeDetection detection = newDetection();
        detection.completeAssessed(DetectionBand.UNDETERMINED, AssessmentBasis.MODEL, UndeterminedReason.LOW_RESOLUTION,
                null, null, null, List.of(), "/uploads/h.png", "cf", "v", List.of());

        DeepfakeDetectionResponse response = DeepfakeDetectionResponse.from(detection);

        assertThat(response.band()).isEqualTo("UNDETERMINED");
        assertThat(response.undeterminedReason()).isEqualTo("LOW_RESOLUTION");
        assertThat(response.verdict()).isNull();
        assertThat(response.provenanceSignals()).isEmpty();
    }

    @Test
    void LLM_모드나_이전_기록은_새_필드가_비어_있다() {
        DeepfakeDetection detection = newDetection();
        detection.complete(Verdict.SUSPICIOUS, 0.6, 55, List.of(), "/uploads/h.png", false);

        DeepfakeDetectionResponse response = DeepfakeDetectionResponse.from(detection);

        assertThat(response.band()).isNull();
        assertThat(response.modelName()).isNull();
        assertThat(response.provenanceSignals()).isEmpty();
        assertThat(response.riskScore()).isEqualTo(55);
    }

    @Test
    void 웹_검색_사용_가능_여부를_함께_내려준다() {
        DeepfakeDetection detection = DeepfakeDetection.builder().fileUrl("/uploads/a.png").build();

        assertThat(DeepfakeDetectionResponse.from(detection, null, true).webSearchAvailable()).isTrue();
        assertThat(DeepfakeDetectionResponse.from(detection).webSearchAvailable()).isFalse();
    }
}
