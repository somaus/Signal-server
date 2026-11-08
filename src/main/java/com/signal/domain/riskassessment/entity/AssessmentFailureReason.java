package com.signal.domain.riskassessment.entity;

/**
 * 위험도 분석이 FAILED로 끝난 이유. 딥페이크 탐지의 실패 사유와 같은 이름을 써서 프론트가 같은 방식으로
 * 안내할 수 있게 한다.
 */
public enum AssessmentFailureReason {
    NO_FACE_DETECTED,
    AI_SERVER_ERROR,
    TIMEOUT
}
