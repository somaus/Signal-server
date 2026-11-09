package com.signal.domain.riskassessment.analyzer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** risk-lab의 {@code POST /analyze} 응답. 점수는 없고 측정값만 있다. 모르는 필드는 무시한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
record RiskMeasurement(String model, String modelVersion, Integer width, Integer height, Integer faceCount,
                       List<Face> faces) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Face(Box box, Double confidence, Double areaRatio, Integer facePx, Double sharpness,
                Double frontal, Double brightness) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Box(int x, int y, int width, int height) {
    }

    List<Face> facesOrEmpty() {
        return faces == null ? List.of() : faces;
    }

    /** risk-lab이 검출한 전체 얼굴 수. faces는 상한까지만 오므로 응답의 faceCount를 쓰고, 없으면 faces 개수. */
    int detectedFaceCount() {
        return faceCount != null ? Math.max(faceCount, facesOrEmpty().size()) : facesOrEmpty().size();
    }
}
