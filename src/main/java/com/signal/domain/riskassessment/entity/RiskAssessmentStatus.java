package com.signal.domain.riskassessment.entity;

/** 위험도 분석의 진행 상태. 요청은 PROCESSING으로 접수되고, 분석이 끝나면 COMPLETED 또는 FAILED가 된다. */
public enum RiskAssessmentStatus {
    PROCESSING, COMPLETED, FAILED
}
