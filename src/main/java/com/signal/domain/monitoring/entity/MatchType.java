package com.signal.domain.monitoring.entity;

/** 역이미지 검색이 알려 주는 일치 종류. 점수가 아니라 구분이다. */
public enum MatchType {
    FULL,      // 동일 이미지
    PARTIAL,   // 일부 일치(잘라냈거나 편집한 이미지)
    PAGE,      // 같은 페이지에 노출(이미지 일치 여부는 확인되지 않음)
    SIMILAR    // 시각적으로 비슷한 이미지(같은 이미지라는 뜻이 아님)
}
