package com.signal.domain.monitoring.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MonitoringTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
    private final Duration interval = Duration.ofHours(6);

    private Monitoring monitoring() {
        return Monitoring.builder().userId(1L).referenceImageUrl("ref.jpg").build();
    }

    @Test
    void 새로_만들면_ACTIVE이고_바로_검색_대상이다() {
        Monitoring m = monitoring();

        assertThat(m.getStatus()).isEqualTo(MonitoringStatus.ACTIVE);
        assertThat(m.getNextSearchAt()).isEqualTo(m.getCreatedAt());
        assertThat(m.getConsecutiveFailures()).isZero();
    }

    @Test
    void 실패가_한도에_못_미치면_ACTIVE를_유지하고_다음_주기로_미룬다() {
        Monitoring m = monitoring();

        m.recordSearchFailure(now, interval, 3);
        m.recordSearchFailure(now, interval, 3);

        assertThat(m.getStatus()).isEqualTo(MonitoringStatus.ACTIVE);
        assertThat(m.getConsecutiveFailures()).isEqualTo(2);
        assertThat(m.getNextSearchAt()).isEqualTo(now.plus(interval));
    }

    @Test
    void 연속_실패가_한도에_이르면_PAUSED가_된다() {
        Monitoring m = monitoring();

        for (int i = 0; i < 3; i++) {
            m.recordSearchFailure(now, interval, 3);
        }

        assertThat(m.getStatus()).isEqualTo(MonitoringStatus.PAUSED);
    }

    @Test
    void 성공하면_연속_실패가_초기화된다() {
        Monitoring m = monitoring();
        m.recordSearchFailure(now, interval, 3);
        m.recordSearchFailure(now, interval, 3);

        m.recordSearchSuccess(now, interval);
        m.recordSearchFailure(now, interval, 3);

        assertThat(m.getConsecutiveFailures()).isEqualTo(1);
        assertThat(m.getStatus()).isEqualTo(MonitoringStatus.ACTIVE);
        assertThat(m.getLastSearchedAt()).isEqualTo(now);
    }

    @Test
    void 치명적_실패는_한_번에_PAUSED가_된다() {
        Monitoring m = monitoring();

        m.pauseAfterFatalFailure(now);

        assertThat(m.getStatus()).isEqualTo(MonitoringStatus.PAUSED);
        assertThat(m.getConsecutiveFailures()).isEqualTo(1);
        assertThat(m.getLastSearchedAt()).isEqualTo(now);
    }

    @Test
    void 소유자만_isOwnedBy가_참이다() {
        Monitoring m = monitoring();

        assertThat(m.isOwnedBy(1L)).isTrue();
        assertThat(m.isOwnedBy(2L)).isFalse();
        assertThat(m.isOwnedBy(null)).isFalse();
    }
}
