package com.signal.domain.auth.service;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.mail.MailService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 인증번호 발송 → 확인 → verificationToken 발급까지 담당.
 * 저장소는 인메모리(ConcurrentHashMap). 서버 여러 대로 확장하면 Redis로 교체할 것.
 */
@Service
@RequiredArgsConstructor
public class VerificationService {

    public enum Purpose { SIGNUP, PASSWORD_RESET }

    private record CodeEntry(String code, long sentAt, long expiresAt, int failedAttempts) {}

    private record TokenEntry(String email, Purpose purpose, long expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RESEND_COOLDOWN_MS = 30_000;   // 같은 주소로 메일 폭탄을 보내지 못하게 하는 재발송 간격
    private static final long SEND_WINDOW_MS = 3_600_000;   // 발송 횟수를 세는 구간(1시간)
    private static final int MAX_SENDS_PER_WINDOW = 5;   // 구간 내 주소별 최대 발송 횟수
    private static final int MAX_FAILED_ATTEMPTS = 5;   // 6자리 숫자 무차별 대입 방지

    private final MailService mailService;

    @Value("${verification.code-expiration}")
    private long codeExpiration;

    @Value("${verification.token-expiration}")
    private long tokenExpiration;

    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    /** 주소별 발송 시각. 오답으로 인증번호가 폐기돼도 남겨 재발송 간격과 횟수 제한이 풀리지 않게 한다. */
    private final Map<String, Deque<Long>> sends = new ConcurrentHashMap<>();
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();

    /** 인증번호 생성·저장 후 메일 발송. 유효시간(초) 반환 */
    public long sendCode(String email, Purpose purpose) {
        purgeExpired();
        String key = key(email, purpose);
        long now = now();
        // 검사와 기록을 compute 안에서 함께 해 동시 요청이 한도를 넘지 못하게 한다. 예외가 나면 기록은 바뀌지 않는다.
        sends.compute(key, (k, times) -> {
            Deque<Long> recent = times == null ? new ArrayDeque<>() : times;
            if (!recent.isEmpty() && now - recent.peekLast() < RESEND_COOLDOWN_MS
                    || recent.size() >= MAX_SENDS_PER_WINDOW) {
                throw new SignalException(ErrorCode.VERIFICATION_TOO_FREQUENT);
            }
            recent.addLast(now);
            return recent;
        });

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        codes.put(key, new CodeEntry(code, now, now + codeExpiration, 0));
        mailService.sendVerificationCode(email.trim(), code);
        return codeExpiration / 1000;
    }

    /** 인증번호 확인 성공 시 verificationToken 발급 */
    public String verifyCode(String email, String code, Purpose purpose) {
        String key = key(email, purpose);
        CodeEntry entry = codes.get(key);

        if (entry == null || now() > entry.expiresAt()) {
            throw new SignalException(ErrorCode.CODE_EXPIRED);
        }
        if (!MessageDigest.isEqual(
                entry.code().getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            recordFailedAttempt(key);
            throw new SignalException(ErrorCode.CODE_MISMATCH);
        }

        // 동시에 같은 인증번호로 여러 번 통과하지 못하도록 제거에 성공한 요청만 토큰을 받는다.
        if (!codes.remove(key, entry)) {
            throw new SignalException(ErrorCode.CODE_EXPIRED);
        }

        String token = "vrf_" + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new TokenEntry(normalize(email), purpose, now() + tokenExpiration));
        return token;
    }

    /** verificationToken 검증 후 소모(1회용) */
    public void consumeToken(String token, String email, Purpose purpose) {
        TokenEntry entry = tokens.get(token);

        if (entry == null
                || now() > entry.expiresAt()
                || !entry.email().equals(normalize(email))
                || entry.purpose() != purpose) {
            throw new SignalException(ErrorCode.INVALID_VERIFICATION);
        }

        // get과 remove 사이에 다른 요청이 끼어들어도 1회용이 보장되도록 제거에 성공한 요청만 통과시킨다.
        if (!tokens.remove(token, entry)) {
            throw new SignalException(ErrorCode.INVALID_VERIFICATION);
        }
    }

    /** 확인되지 않고 방치된 인증번호/토큰이 인메모리 맵에 영구히 쌓이지 않도록 만료분을 정리한다. */
    private void purgeExpired() {
        long now = now();
        codes.values().removeIf(entry -> now > entry.expiresAt());
        tokens.values().removeIf(entry -> now > entry.expiresAt());
        for (String k : sends.keySet()) {
            sends.computeIfPresent(k, (key, times) -> {
                times.removeIf(t -> now - t >= SEND_WINDOW_MS);
                return times.isEmpty() ? null : times;
            });
        }
    }

    /** 실패 횟수가 한도에 도달하면 인증번호를 폐기해 재발송 없이는 더 시도할 수 없게 한다. */
    private void recordFailedAttempt(String key) {
        codes.computeIfPresent(key, (k, current) -> current.failedAttempts() + 1 >= MAX_FAILED_ATTEMPTS
                ? null
                : new CodeEntry(current.code(), current.sentAt(), current.expiresAt(), current.failedAttempts() + 1));
    }

    private String key(String email, Purpose purpose) {
        return purpose + ":" + normalize(email);
    }

    /** 메일 주소는 대소문자·앞뒤 공백이 달라도 같은 주소다. 인증은 "A@x.com", 가입은 "a@x.com"으로 하면 거부되던 것을 막는다. */
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
