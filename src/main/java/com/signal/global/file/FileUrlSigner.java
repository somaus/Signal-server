package com.signal.global.file;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 민감한 업로드 파일(/uploads/**, 프로필 사진 제외)의 URL에 만료 시각과 HMAC 서명을 붙이고 검증한다.
 * DB에는 서명 없는 경로를 그대로 두고, API 응답을 만들 때만 서명한다.
 */
@Component
public class FileUrlSigner {

    // 프로필 사진만 공개한다. 나머지 하위 경로는 모두 서명이 필요하다.
    private static final String PUBLIC_PREFIX = "/uploads/profile/";

    private final byte[] secret;
    private final long ttlSeconds;
    private final Clock clock;

    @Autowired
    public FileUrlSigner(@Value("${file.url-signing-secret}") String secret,
                         @Value("${file.url-ttl-seconds}") long ttlSeconds) {
        this(secret, ttlSeconds, Clock.systemUTC());
    }

    FileUrlSigner(String secret, long ttlSeconds, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    /** 서명이 필요한 /uploads 경로면 ?exp=&sig=를 붙여 돌려주고, 그 밖의 값(null, 공개 경로, API 경로)은 그대로 돌려준다. */
    public String sign(String url) {
        if (url == null || !requiresSignature(url)) {
            return url;
        }
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        return url + "?exp=" + exp + "&sig=" + hmac(url, exp);
    }

    public boolean requiresSignature(String path) {
        return path.startsWith("/uploads/") && !(path.startsWith(PUBLIC_PREFIX) && !path.contains(".."));
    }

    public boolean verify(String path, String exp, String sig) {
        if (exp == null || sig == null) {
            return false;
        }
        try {
            long expSeconds = Long.parseLong(exp);
            return expSeconds >= clock.instant().getEpochSecond()
                    && MessageDigest.isEqual(hmac(path, expSeconds).getBytes(StandardCharsets.UTF_8),
                            sig.getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String hmac(String path, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((path + ":" + exp).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
