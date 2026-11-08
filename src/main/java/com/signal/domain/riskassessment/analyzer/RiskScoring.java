package com.signal.domain.riskassessment.analyzer;

/**
 * 측정값을 0~100 점수로 바꾸는 고정 공식. LLM이 점수를 정하지 않으므로 같은 측정값에는 항상 같은 점수가 나온다.
 *
 * <p>측정값(얼굴 크기, 선명도, 정면도)은 이미지에서 직접 잰 값이다. 반면 아래 공식의 경계값(예: 얼굴 한 변 48px에서 256px)은
 * 합성 모델이 쓰기 쉬운 사진의 조건을 생각해 정한 값이고, 실제 악용 사례로 검증한 위험 모델이 아니다. 그래서 점수는
 * "합성 재료로 쓰기 좋은 정도를 측정값으로 환산한 것"이며, 피해 발생 확률이 아니다. 경계값은 이 클래스의 상수만 바꾸면 된다.
 */
final class RiskScoring {

    // 얼굴 한 변의 픽셀 수: 이 아래는 재료로 쓰기 어렵고, 이 위는 충분하다.
    static final double FACE_PX_LOW = 48;
    static final double FACE_PX_HIGH = 256;
    // 128x128로 줄인 얼굴의 라플라시안 분산: 아래는 흐림, 위는 또렷함.
    static final double SHARPNESS_LOW = 15;
    static final double SHARPNESS_HIGH = 300;
    // 이미지에서 얼굴이 차지하는 면적 비율.
    static final double AREA_LOW = 0.005;
    static final double AREA_HIGH = 0.15;

    private static final double RESOLUTION_WEIGHT = 0.6;
    private static final double SHARPNESS_WEIGHT = 0.4;
    private static final double AREA_WEIGHT = 0.5;
    private static final double FRONTAL_WEIGHT = 0.5;

    private RiskScoring() {
    }

    /** 이미지 품질: 얼굴의 해상도(60%)와 선명도(40%). */
    static int imageQuality(RiskMeasurement.Face face) {
        double resolution = logScale(face.facePx(), FACE_PX_LOW, FACE_PX_HIGH);
        double sharpness = logScale(face.sharpness(), SHARPNESS_LOW, SHARPNESS_HIGH);
        return toScore(RESOLUTION_WEIGHT * resolution + SHARPNESS_WEIGHT * sharpness);
    }

    /** 얼굴 노출도: 화면에서 차지하는 비율(50%)과 정면도(50%). */
    static int faceExposure(RiskMeasurement.Face face) {
        double area = logScale(face.areaRatio(), AREA_LOW, AREA_HIGH);
        double frontal = clamp01(face.frontal() == null ? 0 : face.frontal());
        return toScore(AREA_WEIGHT * area + FRONTAL_WEIGHT * frontal);
    }

    /** value가 low 이하면 0, high 이상이면 1, 그 사이는 로그 눈금으로 보간한다. 값이 없거나 0 이하면 0. */
    static double logScale(Number value, double low, double high) {
        if (value == null || value.doubleValue() <= 0 || Double.isNaN(value.doubleValue())) {
            return 0;
        }
        double t = (Math.log(value.doubleValue()) - Math.log(low)) / (Math.log(high) - Math.log(low));
        return clamp01(t);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int toScore(double unit) {
        return (int) Math.round(clamp01(unit) * 100);
    }
}
