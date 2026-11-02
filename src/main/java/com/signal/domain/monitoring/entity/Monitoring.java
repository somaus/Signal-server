package com.signal.domain.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "monitorings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Monitoring {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String referenceImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MonitoringStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastSearchedAt;

    @Column(nullable = false)
    private LocalDateTime nextSearchAt;

    @Column(nullable = false)
    private int consecutiveFailures;

    @Builder
    public Monitoring(Long userId, String referenceImageUrl) {
        this.userId = userId;
        this.referenceImageUrl = referenceImageUrl;
        this.status = MonitoringStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.nextSearchAt = this.createdAt;
    }

    public void recordSearchSuccess(LocalDateTime now, Duration interval) {
        this.lastSearchedAt = now;
        this.nextSearchAt = now.plus(interval);
        this.consecutiveFailures = 0;
    }

    /** 다시 시도해도 소용없는 실패는 연속 실패를 기다리지 않고 바로 멈춘다. */
    public void pauseAfterFatalFailure(LocalDateTime now) {
        this.lastSearchedAt = now;
        this.consecutiveFailures++;
        this.status = MonitoringStatus.PAUSED;
    }

    /** 실패하면 다음 주기에 다시 시도하고, 연속 실패가 maxFailures에 이르면 멈춘다(PAUSED). */
    public void recordSearchFailure(LocalDateTime now, Duration interval, int maxFailures) {
        this.lastSearchedAt = now;
        this.nextSearchAt = now.plus(interval);
        this.consecutiveFailures++;
        if (this.consecutiveFailures >= maxFailures) {
            this.status = MonitoringStatus.PAUSED;
        }
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
