package com.signal.domain.websearch.entity;

import com.signal.domain.monitoring.entity.MatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 같은 이미지(또는 비슷한 이미지)가 실린 웹 페이지. 외부 서비스가 알려 준 값이라 점수는 없고 일치 종류만 있다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebMatch {

    @Column(name = "page_url", nullable = false, length = 2048)
    private String pageUrl;

    @Column(name = "title", length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", length = 10)
    private MatchType matchType;

    public WebMatch(String pageUrl, String title, MatchType matchType) {
        this.pageUrl = pageUrl;
        this.title = title;
        this.matchType = matchType;
    }
}
