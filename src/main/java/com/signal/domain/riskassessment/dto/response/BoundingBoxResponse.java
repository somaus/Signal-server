package com.signal.domain.riskassessment.dto.response;

import com.signal.domain.riskassessment.entity.BoundingBox;

public record BoundingBoxResponse(int x, int y, int width, int height) {

    /** 좌표를 모르는 얼굴이면 null을 돌려준다. */
    public static BoundingBoxResponse from(BoundingBox boundingBox) {
        if (boundingBox == null) {
            return null;
        }
        return new BoundingBoxResponse(
                boundingBox.getX(), boundingBox.getY(), boundingBox.getWidth(), boundingBox.getHeight());
    }
}
