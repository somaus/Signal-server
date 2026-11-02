package com.signal.domain.websearch.entity;

public enum WebSearchStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    UNAVAILABLE   // 영상이거나 오늘 검색 상한에 도달해 검색하지 않음
}
