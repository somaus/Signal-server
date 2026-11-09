package com.signal.domain.riskassessment.entity;

/**
 * 위험도 분석이 FAILED로 끝난 이유. 딥페이크 탐지의 실패 사유와 같은 이름을 써서 프론트가 같은 방식으로
 * 안내할 수 있게 한다.
 */
public enum AssessmentFailureReason {
    NO_FACE_DETECTED,
    AI_SERVER_ERROR,
    TIMEOUT,
    /** 이미지를 읽을 수 없거나 해상도·용량이 너무 커서 분석기가 거절했다. 같은 이미지로 다시 시도해도 실패한다. */
    INVALID_IMAGE
}
