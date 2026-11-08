package com.signal.domain.riskassessment.analyzer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** risk-lab의 {@code POST /analyze} 응답. 점수는 없고 측정값만 있다. 모르는 필드는 무시한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
record RiskMeasurement(String model, String modelVersion, Integer width, Integer height, List<Face> faces) {

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
}
