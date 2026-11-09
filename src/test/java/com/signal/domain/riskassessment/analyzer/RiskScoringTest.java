package com.signal.domain.riskassessment.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiskScoringTest {

    private static RiskMeasurement.Face face(int facePx, double sharpness, double areaRatio, double frontal) {
        return new RiskMeasurement.Face(new RiskMeasurement.Box(0, 0, facePx, facePx), 0.9, areaRatio, facePx,
                sharpness, frontal, 120.0);
    }

    @Test
    void 작고_흐린_얼굴은_품질_점수가_0이다() {
        assertThat(RiskScoring.imageQuality(face(40, 10, 0.001, 1))).isZero();
    }

    @Test
    void 크고_또렷한_얼굴은_품질_점수가_100이다() {
        assertThat(RiskScoring.imageQuality(face(300, 400, 0.2, 1))).isEqualTo(100);
    }

    @Test
    void 경계값에서_가중치대로_합쳐진다() {
        // 해상도만 최대(60%), 선명도 최소 → 60
        assertThat(RiskScoring.imageQuality(face((int) RiskScoring.FACE_PX_HIGH, 1, 0.05, 1))).isEqualTo(60);
        // 선명도만 최대(40%), 해상도 최소 → 40
        assertThat(RiskScoring.imageQuality(face(10, RiskScoring.SHARPNESS_HIGH, 0.05, 1))).isEqualTo(40);
    }

    @Test
    void 얼굴이_커지면_점수가_오른다() {
        int small = RiskScoring.imageQuality(face(64, 100, 0.02, 1));
        int medium = RiskScoring.imageQuality(face(128, 100, 0.02, 1));
        int large = RiskScoring.imageQuality(face(200, 100, 0.02, 1));
        assertThat(small).isLessThan(medium);
        assertThat(medium).isLessThan(large);
    }

    @Test
    void 노출도는_면적과_정면도를_반반_반영한다() {
        assertThat(RiskScoring.faceExposure(face(100, 100, RiskScoring.AREA_HIGH, 0))).isEqualTo(50);
        assertThat(RiskScoring.faceExposure(face(100, 100, 0.0001, 1))).isEqualTo(50);
        assertThat(RiskScoring.faceExposure(face(100, 100, RiskScoring.AREA_HIGH, 1))).isEqualTo(100);
        assertThat(RiskScoring.faceExposure(face(100, 100, 0.0001, 0))).isZero();
    }

    @Test
    void 측정값이_없으면_0으로_본다() {
        RiskMeasurement.Face empty = new RiskMeasurement.Face(null, null, null, null, null, null, null);
        assertThat(RiskScoring.imageQuality(empty)).isZero();
        assertThat(RiskScoring.faceExposure(empty)).isZero();
    }

    @Test
    void 정면도가_범위를_벗어나도_0에서_100_사이다() {
        assertThat(RiskScoring.faceExposure(face(100, 100, 1.0, 5))).isEqualTo(100);
        assertThat(RiskScoring.faceExposure(face(100, 100, 0.0001, -3))).isZero();
    }

    @Test
    void 같은_입력은_항상_같은_점수다() {
        RiskMeasurement.Face f = face(123, 77, 0.04, 0.8);
        assertThat(RiskScoring.imageQuality(f)).isEqualTo(RiskScoring.imageQuality(f));
        assertThat(RiskScoring.faceExposure(f)).isEqualTo(RiskScoring.faceExposure(f));
    }

    @Test
    void 종합은_품질_40_노출도_40_메타데이터_20의_가중_합이다() {
        assertThat(RiskScoring.overall(100, 100, 100)).isEqualTo(100);
        assertThat(RiskScoring.overall(0, 0, 0)).isZero();
        assertThat(RiskScoring.overall(100, 0, 0)).isEqualTo(40);
        assertThat(RiskScoring.overall(0, 100, 0)).isEqualTo(40);
        assertThat(RiskScoring.overall(0, 0, 100)).isEqualTo(20);
        assertThat(RiskScoring.overall(60, 80, 10)).isEqualTo(58);   // 24 + 32 + 2
    }

    @Test
    void 위치_정보가_없어도_얼굴_기반_두_항목이_충분히_높으면_높음_구간에_닿는다() {
        // 평균이었다면 (100 + 100 + 10) / 3 = 70이 한계였다. 가중 합에서는 두 항목이 85면 이미 70을 넘는다.
        assertThat(RiskScoring.overall(85, 85, 10)).isGreaterThanOrEqualTo(70);
        assertThat(RiskScoring.overall(100, 100, 10)).isEqualTo(82);
    }

    @Test
    void 종합은_0에서_100을_벗어나지_않는다() {
        assertThat(RiskScoring.overall(500, 500, 500)).isEqualTo(100);
        assertThat(RiskScoring.overall(-50, -50, -50)).isZero();
    }
}
