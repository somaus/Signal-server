package com.signal.domain.websearch.client;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 외부 검색 서비스는 이미지를 base64가 아니라 공개 URL로만 받는다. 검색하는 동안만 추측할 수 없는 토큰 주소로
 * 이미지를 열어 두고(기본 90초), 검색이 끝나면 바로 닫는다. 메모리에만 두며 서버가 여러 대이면 쓸 수 없다.
 */
@Component
public class TempImageStore {

    public record Entry(byte[] content, String contentType) {
    }

    private record Stored(Entry entry, long expiresAtMs) {
    }

    private final Map<String, Stored> images = new ConcurrentHashMap<>();
    private final long ttlMs;

    public TempImageStore(@Value("${web-search.temp-image-ttl-seconds:90}") long ttlSeconds) {
        this.ttlMs = Duration.ofSeconds(ttlSeconds).toMillis();
    }

    public String put(byte[] content, String contentType) {
        purgeExpired();
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        images.put(token, new Stored(new Entry(content, contentType), System.currentTimeMillis() + ttlMs));
        return token;
    }

    /** 만료됐거나 없으면 null. */
    public Entry get(String token) {
        Stored stored = images.get(token);
        if (stored == null) {
            return null;
        }
        if (stored.expiresAtMs() < System.currentTimeMillis()) {
            images.remove(token);
            return null;
        }
        return stored.entry();
    }

    public void remove(String token) {
        images.remove(token);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        images.values().removeIf(stored -> stored.expiresAtMs() < now);
    }
}
