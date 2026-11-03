package com.signal.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.signal.domain.auth.dto.response.TokenResponse;
import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.security.jwt.JwtProvider;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** 서명·만료 검증은 실제 JwtProvider로 확인하고, 사용자 조회만 목으로 대체한다. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private VerificationService verificationService;

    private JwtProvider jwtProvider;
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider();
        ReflectionTestUtils.setField(jwtProvider, "secret",
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
        ReflectionTestUtils.setField(jwtProvider, "accessTokenExpiration", 3_600_000L);
        ReflectionTestUtils.setField(jwtProvider, "refreshTokenExpiration", 1_209_600_000L);
        ReflectionTestUtils.invokeMethod(jwtProvider, "init");

        authService = new AuthService(userRepository, passwordEncoder, jwtProvider, verificationService);
        ReflectionTestUtils.setField(authService, "accessTokenExpiration", 3_600_000L);

        user = User.builder().email("user@example.com").password("encoded").name("사용자").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    private void assertInvalidRefreshToken(String refreshToken) {
        assertThatThrownBy(() -> authService.reissue(refreshToken))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    void 유효한_리프레시_토큰이면_새_토큰을_발급한다() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        String refreshToken = jwtProvider.createRefreshToken(USER_ID, user.getTokenVersion());

        TokenResponse response = authService.reissue(refreshToken);

        assertThat(jwtProvider.validateToken(response.accessToken())).isTrue();
        assertThat(jwtProvider.isRefreshToken(response.accessToken())).isFalse();
        assertThat(jwtProvider.isRefreshToken(response.refreshToken())).isTrue();
        assertThat(response.accessTokenExpiresIn()).isEqualTo(3_600L);
    }

    @Test
    void 리프레시_토큰이_없거나_비어_있거나_깨졌으면_거부한다() {
        assertInvalidRefreshToken(null);
        assertInvalidRefreshToken("");
        assertInvalidRefreshToken("not.a.jwt");
    }

    @Test
    void 액세스_토큰으로는_재발급할_수_없다() {
        assertInvalidRefreshToken(jwtProvider.createAccessToken(USER_ID, user.getTokenVersion()));
    }

    @Test
    void 사용자가_없으면_거부한다() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertInvalidRefreshToken(jwtProvider.createRefreshToken(USER_ID, 0));
    }

    @Test
    void 로그아웃하면_이전에_발급한_리프레시_토큰으로_재발급할_수_없다() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        String refreshToken = jwtProvider.createRefreshToken(USER_ID, user.getTokenVersion());

        authService.logout(USER_ID);

        assertThat(user.getTokenVersion()).isEqualTo(1);
        assertInvalidRefreshToken(refreshToken);
        // 로그아웃 이후 새로 발급한 토큰은 통한다.
        assertThat(authService.reissue(jwtProvider.createRefreshToken(USER_ID, user.getTokenVersion()))).isNotNull();
    }

    @Test
    void 없는_사용자의_로그아웃은_NOT_FOUND다() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.logout(USER_ID))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void 비밀번호를_재설정하면_이전_리프레시_토큰으로_재발급할_수_없다() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        String oldRefreshToken = jwtProvider.createRefreshToken(USER_ID, user.getTokenVersion());

        authService.resetPassword(new com.signal.domain.auth.dto.request.ResetPasswordRequest(
                "user@example.com", "vrf_token", "newpassword1", "newpassword1"));

        assertInvalidRefreshToken(oldRefreshToken);
    }
}
