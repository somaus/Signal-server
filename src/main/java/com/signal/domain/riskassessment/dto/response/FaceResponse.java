package com.signal.domain.riskassessment.dto.response;

import com.signal.domain.riskassessment.entity.DetectedFace;

/**
 * 검출된 얼굴. positionKnown이 false이면 얼굴은 있지만 위치를 알 수 없어 boundingBox가 null이다(화면은 박스를 그리지 않는다).
 * 좌표는 범용 비전 LLM의 추정치라 정확하지 않을 수 있으니 "참고용"으로 보여 준다.
 */
public record FaceResponse(int index, BoundingBoxResponse boundingBox, boolean positionKnown) {

    public static FaceResponse from(DetectedFace detectedFace) {
        BoundingBoxResponse box = BoundingBoxResponse.from(detectedFace.getBoundingBox());
        return new FaceResponse(detectedFace.getFaceIndex(), box, box != null);
    }
}
