package com.signal.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

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
}
