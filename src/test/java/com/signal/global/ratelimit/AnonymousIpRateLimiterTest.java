package com.signal.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AnonymousIpRateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final AnonymousIpRateLimiter limiter = new AnonymousIpRateLimiter(2, Duration.ofMinutes(1), now::get);

    @Test
    void 한도까지만_허용하고_넘으면_막는다() {
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    void IP마다_따로_센다() {
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("1.1.1.1");
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();
    }

    @Test
    void 윈도우가_지나면_다시_허용한다() {
        limiter.tryAcquire("1.1.1.1");
        limiter.tryAcquire("1.1.1.1");
        now.set(Duration.ofMinutes(1).toMillis());
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test
    void 기록이_많이_쌓이면_윈도우가_지난_IP만_정리한다() {
        for (int i = 0; i < 6_000; i++) {
            limiter.tryAcquire("old-" + i);
        }
        now.set(Duration.ofSeconds(30).toMillis());
        for (int i = 0; i < 4_001; i++) {
            limiter.tryAcquire("fresh-" + i);
        }
        now.set(Duration.ofSeconds(61).toMillis());

        limiter.tryAcquire("trigger");

        @SuppressWarnings("unchecked")
        Map<String, Object> windows = (Map<String, Object>) ReflectionTestUtils.getField(limiter, "windows");
        assertThat(windows).hasSize(4_002);
        assertThat(windows).containsKey("fresh-0").doesNotContainKey("old-0");
    }

    @Test
    void 윈도우_길이와_정확히_같게_지난_기록도_정리한다() {
        for (int i = 0; i < 10_001; i++) {
            limiter.tryAcquire("ip-" + i);
        }
        now.set(Duration.ofMinutes(1).toMillis());

        limiter.tryAcquire("trigger");

        @SuppressWarnings("unchecked")
        Map<String, Object> windows = (Map<String, Object>) ReflectionTestUtils.getField(limiter, "windows");
        assertThat(windows).hasSize(1);
    }
}
