package com.signal.global.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FileUrlSignerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private FileUrlSigner signerAt(Instant now) {
        return new FileUrlSigner("secret", 3600, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String param(String url, String name) {
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    @Test
    void 서명한_URL은_유효기간_안에서_검증된다() {
        String signed = signerAt(NOW).sign("/uploads/protections/a.png");

        assertThat(signed).startsWith("/uploads/protections/a.png?exp=" + (NOW.getEpochSecond() + 3600));
        assertThat(signerAt(NOW.plusSeconds(3600)).verify("/uploads/protections/a.png",
                param(signed, "exp"), param(signed, "sig"))).isTrue();
    }

    @Test
    void 만료된_서명은_거절한다() {
        String signed = signerAt(NOW).sign("/uploads/protections/a.png");

        assertThat(signerAt(NOW.plusSeconds(3601)).verify("/uploads/protections/a.png",
                param(signed, "exp"), param(signed, "sig"))).isFalse();
    }

    @Test
    void 경로를_바꾸면_거절한다() {
        String signed = signerAt(NOW).sign("/uploads/protections/a.png");

        assertThat(signerAt(NOW).verify("/uploads/protections/b.png",
                param(signed, "exp"), param(signed, "sig"))).isFalse();
    }

    @Test
    void 만료시각을_바꾸면_거절한다() {
        String signed = signerAt(NOW).sign("/uploads/protections/a.png");
        String longer = String.valueOf(NOW.getEpochSecond() + 999999);

        assertThat(signerAt(NOW).verify("/uploads/protections/a.png", longer, param(signed, "sig"))).isFalse();
    }

    @Test
    void 서명이_없거나_exp가_숫자가_아니면_거절한다() {
        assertThat(signerAt(NOW).verify("/uploads/a/b.png", null, null)).isFalse();
        assertThat(signerAt(NOW).verify("/uploads/a/b.png", "abc", "x")).isFalse();
    }

    @Test
    void 프로필_사진과_null과_업로드가_아닌_경로는_서명하지_않는다() {
        FileUrlSigner signer = signerAt(NOW);

        assertThat(signer.sign("/uploads/profile/a.png")).isEqualTo("/uploads/profile/a.png");
        assertThat(signer.sign(null)).isNull();
        assertThat(signer.sign("/api/v1/reports/1/document")).isEqualTo("/api/v1/reports/1/document");
        assertThat(signer.requiresSignature("/uploads/profile/../protections/a.png")).isTrue();
    }

    @Test
    void 인코딩된_상위_경로나_구분자가_있으면_프로필_경로라도_서명이_필요하다() {
        FileUrlSigner signer = signerAt(NOW);

        assertThat(signer.requiresSignature("/uploads/profile/%2e%2e/protections/a.png")).isTrue();
        assertThat(signer.requiresSignature("/uploads/profile/%2E%2E%2Fprotections%2Fa.png")).isTrue();
        assertThat(signer.requiresSignature("/uploads/profile/%252e%252e/protections/a.png")).isTrue();
        assertThat(signer.requiresSignature("/uploads/profile/..%2Fprotections/a.png")).isTrue();
        assertThat(signer.requiresSignature("/uploads/profile/sub/a.png")).isTrue();
        assertThat(signer.requiresSignature("/uploads/profile/a.png")).isFalse();
    }
}
