package com.signal.domain.websearch.entity;

public enum UnavailableReason {
    VIDEO,           // 영상은 검색하지 않는다
    OWNER_LIMIT,     // 이 사용자의 오늘 검색 횟수를 다 썼다
    DAILY_LIMIT,     // 서비스 전체의 오늘 검색 상한에 도달했다
    MONTHLY_LIMIT    // 이번 달 무료 한도 보호용 상한에 도달했다
}
