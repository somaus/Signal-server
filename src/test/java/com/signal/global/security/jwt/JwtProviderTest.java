package com.signal.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtProviderTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private static JwtProvider provider(String secret, long accessMs, long refreshMs) {
        JwtProvider provider = new JwtProvider();
        ReflectionTestUtils.setField(provider, "secret", secret);
        ReflectionTestUtils.setField(provider, "accessTokenExpiration", accessMs);
        ReflectionTestUtils.setField(provider, "refreshTokenExpiration", refreshMs);
        provider.init();
        return provider;
    }

    private final JwtProvider jwtProvider = provider(SECRET, 60_000, 120_000);

    @Test
    void 액세스_토큰에서_사용자와_토큰_버전을_읽는다() {
        String token = jwtProvider.createAccessToken(42L, 3);

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(42L);
        assertThat(jwtProvider.getTokenVersion(token)).isEqualTo(3);
        assertThat(jwtProvider.isRefreshToken(token)).isFalse();
    }

    @Test
    void 리프레시_토큰은_type으로_구분된다() {
        assertThat(jwtProvider.isRefreshToken(jwtProvider.createRefreshToken(1L, 0))).isTrue();
    }

    @Test
    void 만료된_토큰은_유효하지_않다() {
        String expired = provider(SECRET, -1_000, -1_000).createAccessToken(1L, 0);

        assertThat(jwtProvider.validateToken(expired)).isFalse();
    }

    @Test
    void 다른_키로_서명한_토큰은_유효하지_않다() {
        String otherSecret = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());
        String forged = provider(otherSecret, 60_000, 60_000).createAccessToken(1L, 0);

        assertThat(jwtProvider.validateToken(forged)).isFalse();
    }

    @Test
    void 형식이_잘못되거나_비어있는_토큰은_유효하지_않다() {
        assertThat(jwtProvider.validateToken("not.a.jwt")).isFalse();
        assertThat(jwtProvider.validateToken("")).isFalse();
        assertThat(jwtProvider.validateToken(null)).isFalse();
    }

    @Test
    void tokenVersion_클레임이_없는_토큰은_어떤_버전과도_맞지_않는다() {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
        String legacy = Jwts.builder().subject("1").claim("type", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(key).compact();

        assertThat(jwtProvider.getTokenVersion(legacy)).isEqualTo(-1);
    }
}
