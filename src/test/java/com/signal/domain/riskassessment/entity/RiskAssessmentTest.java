package com.signal.domain.riskassessment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RiskAssessmentTest {

    @Test
    void status를_주지_않으면_COMPLETED로_만든다() {
        RiskAssessment ra = RiskAssessment.builder().imageUrl("i.jpg").build();

        assertThat(ra.getStatus()).isEqualTo(RiskAssessmentStatus.COMPLETED);
    }

    @Test
    void processing은_결과가_비어_있는_PROCESSING이다() {
        RiskAssessment ra = RiskAssessment.processing(1L, null, "i.jpg");

        assertThat(ra.getStatus()).isEqualTo(RiskAssessmentStatus.PROCESSING);
        assertThat(ra.getOverallRiskLevel()).isNull();
        assertThat(ra.getOverallScore()).isNull();
        assertThat(ra.getFactors()).isEmpty();
    }

    @Test
    void 실패한_뒤_complete하면_COMPLETED가_되고_실패_사유가_지워진다() {
        RiskAssessment ra = RiskAssessment.processing(1L, null, "i.jpg");
        ra.fail(AssessmentFailureReason.TIMEOUT);

        ra.complete(RiskLevel.HIGH, 80, true, List.of(new RiskFactor("t", "l", 80, "d")), List.of("가리세요"),
                List.of(new DetectedFace(0, new BoundingBox(1, 2, 3, 4))), true);

        assertThat(ra.getStatus()).isEqualTo(RiskAssessmentStatus.COMPLETED);
        assertThat(ra.getFailureReason()).isNull();
        assertThat(ra.getOverallRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(ra.getOverallScore()).isEqualTo(80);
        assertThat(ra.isFaceDetected()).isTrue();
        assertThat(ra.isFallbackUsed()).isTrue();
        assertThat(ra.getFactors()).hasSize(1);
        assertThat(ra.getRecommendations()).containsExactly("가리세요");
        assertThat(ra.getFaces()).hasSize(1);
    }

    @Test
    void complete를_다시_하면_목록을_덧붙이지_않고_바꾼다() {
        RiskAssessment ra = RiskAssessment.processing(1L, null, "i.jpg");
        ra.complete(RiskLevel.LOW, 10, false, List.of(), List.of("a", "b"), List.of(), false);

        ra.complete(RiskLevel.LOW, 10, false, List.of(), List.of("c"), List.of(), false);

        assertThat(ra.getRecommendations()).containsExactly("c");
    }

    @Test
    void fail하면_FAILED와_사유가_남는다() {
        RiskAssessment ra = RiskAssessment.processing(1L, null, "i.jpg");

        ra.fail(AssessmentFailureReason.NO_FACE_DETECTED);

        assertThat(ra.getStatus()).isEqualTo(RiskAssessmentStatus.FAILED);
        assertThat(ra.getFailureReason()).isEqualTo(AssessmentFailureReason.NO_FACE_DETECTED);
    }

    @Test
    void 회원_소유면_userId로만_판단한다() {
        RiskAssessment ra = RiskAssessment.processing(1L, "anon", "i.jpg");

        assertThat(ra.isOwnedBy(1L, null)).isTrue();
        assertThat(ra.isOwnedBy(2L, "anon")).isFalse();
        assertThat(ra.isOwnedBy(null, "anon")).isFalse();
    }

    @Test
    void 비회원_소유면_anonymousId로_판단한다() {
        RiskAssessment ra = RiskAssessment.processing(null, "anon", "i.jpg");

        assertThat(ra.isOwnedBy(null, "anon")).isTrue();
        assertThat(ra.isOwnedBy(1L, "other")).isFalse();
    }
}
