package com.signal.global.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 비로그인 쓰기 요청을 IP 기준 고정 윈도우로 제한한다. X-Anonymous-Id는 클라이언트가 마음대로 바꿀 수 있어 이것만으로는 우회된다.
 * shortcut: 인스턴스 메모리라 서버가 여러 대면 대수만큼 한도가 늘고 재시작하면 초기화된다. 다중 인스턴스가 되면 Redis로 옮긴다.
 */
public class AnonymousIpRateLimiter {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long startMillis, int count) {}

    private final int maxRequests;
    private final long windowMillis;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AnonymousIpRateLimiter(int maxRequests, Duration window, LongSupplier clock) {
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /** 허용되면 true, 한도를 넘으면 false. */
    public boolean tryAcquire(String ip) {
        long now = clock.getAsLong();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
        }
        boolean[] allowed = {true};
        windows.compute(ip, (k, w) -> {
            if (w == null || now - w.startMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            if (w.count() >= maxRequests) {
                allowed[0] = false;
                return w;
            }
            return new Window(w.startMillis(), w.count() + 1);
        });
        return allowed[0];
    }
}
