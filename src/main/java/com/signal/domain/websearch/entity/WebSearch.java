package com.signal.domain.websearch.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 탐지한 사진이 웹 어딘가에 올라와 있는지 역이미지 검색한 결과. DeepfakeDetection과 분리해 둔 이유는, 합성 분석 쪽이
 * 엔티티를 통째로 다시 저장할 때 이 결과를 옛 값으로 덮어쓰지 않게 하기 위해서다.
 */
@Entity
@Table(name = "web_searches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long detectionId;

    /** 한도를 세기 위한 소유자 키("u:사용자id" 또는 "a:익명id"). */
    @Column(length = 100)
    private String ownerKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebSearchStatus status;

    /** status가 UNAVAILABLE일 때의 이유. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UnavailableReason unavailableReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "web_search_matches", joinColumns = @JoinColumn(name = "web_search_id"))
    @OrderColumn(name = "match_order")
    private List<WebMatch> matches = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private WebSearch(Long detectionId, String ownerKey, WebSearchStatus status) {
        this.detectionId = detectionId;
        this.ownerKey = ownerKey;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public static WebSearch processing(Long detectionId, String ownerKey) {
        return new WebSearch(detectionId, ownerKey, WebSearchStatus.PROCESSING);
    }

    public static WebSearch unavailable(Long detectionId, String ownerKey, UnavailableReason reason) {
        WebSearch webSearch = new WebSearch(detectionId, ownerKey, WebSearchStatus.UNAVAILABLE);
        webSearch.unavailableReason = reason;
        return webSearch;
    }

    public void complete(List<WebMatch> matches) {
        this.status = WebSearchStatus.COMPLETED;
        this.matches = new ArrayList<>(matches);
    }

    public void fail() {
        this.status = WebSearchStatus.FAILED;
    }
}
