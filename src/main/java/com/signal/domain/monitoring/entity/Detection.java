package com.signal.domain.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "detections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Detection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long monitoringId;

    @Column(nullable = false, length = 2048)
    private String sourceUrl;

    @Column(length = 2048)
    private String thumbnailUrl;

    @Column(length = 500)
    private String title;

    /** 스텁의 시연용 값. 실제 검색 결과에는 점수가 없어 null이고 {@link #matchType}을 쓴다. */
    private Double similarity;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MatchType matchType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime detectedAt;

    @Builder
    public Detection(Long monitoringId, String sourceUrl, String thumbnailUrl, String title, Double similarity,
                     MatchType matchType) {
        this.monitoringId = monitoringId;
        this.sourceUrl = sourceUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.title = title;
        this.similarity = similarity;
        this.matchType = matchType;
        this.detectedAt = LocalDateTime.now();
    }
}
