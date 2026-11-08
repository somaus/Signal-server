package com.signal.domain.riskassessment.entity;

import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoundingBox {

    // 좌표를 모르는 얼굴은 boundingBox 자체가 null이라 저장 컬럼은 비어 있다(primitive면 NOT NULL 컬럼이 된다).
    private Integer x;
    private Integer y;
    private Integer width;
    private Integer height;

    @Builder
    public BoundingBox(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
}
