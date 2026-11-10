package com.signal.domain.websearch.service;

/** 웹 검색 기록의 소유자 키. 한도를 세고 탈퇴 때 지우는 기준이다. */
public final class WebSearchOwnerKey {

    private WebSearchOwnerKey() {
    }

    public static String user(Long userId) {
        return "u:" + userId;
    }

    public static String anonymous(String anonymousId) {
        return "a:" + anonymousId;
    }
}
