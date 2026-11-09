package com.signal.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.signal.domain.auth.dto.request.LoginRequest;
import com.signal.domain.auth.dto.request.ResetPasswordRequest;
import com.signal.domain.auth.dto.response.TokenResponse;
import com.signal.domain.auth.entity.RefreshToken;
import com.signal.domain.auth.repository.RefreshTokenRepository;
import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.security.jwt.JwtProvider;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    /** 리프레시 토큰 저장소를 메모리로 흉내 내, 회전과 폐기가 실제 흐름대로 이어지는지 확인한다. */
    private final Map<String, RefreshToken> stored = new LinkedHashMap<>();

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

        authService = new AuthService(userRepository, passwordEncoder, jwtProvider, verificationService,
                refreshTokenRepository);
        ReflectionTestUtils.setField(authService, "accessTokenExpiration", 3_600_000L);
        ReflectionTestUtils.setField(authService, "refreshReuseGraceSeconds", 15L);

        user = User.builder().email("user@example.com").password("encoded").name("사용자").build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        lenient().when(passwordEncoder.matches("pw", "encoded")).thenReturn(true);
        lenient().when(refreshTokenRepository.save(org.mockito.ArgumentMatchers.any(RefreshToken.class)))
                .thenAnswer(i -> {
                    RefreshToken t = i.getArgument(0);
                    stored.put(t.getJti(), t);
                    return t;
                });
        lenient().when(refreshTokenRepository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.<String>getArgument(0))));
        lenient().when(refreshTokenRepository.markUsed(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.time.LocalDateTime.class)))
                .thenAnswer(i -> {
                    RefreshToken t = stored.get(i.<String>getArgument(0));
                    if (t == null || t.isUsed()) {
                        return 0;
                    }
                    ReflectionTestUtils.setField(t, "used", true);
                    ReflectionTestUtils.setField(t, "usedAt", i.<java.time.LocalDateTime>getArgument(1));
                    return 1;
                });
        lenient().doAnswer(i -> stored.values().removeIf(t -> t.getSessionId().equals(i.getArgument(0))))
                .when(refreshTokenRepository).deleteBySessionId(org.mockito.ArgumentMatchers.anyString());
        lenient().doAnswer(i -> stored.values().removeIf(t -> t.getUserId().equals(i.getArgument(0))))
                .when(refreshTokenRepository).deleteByUserId(org.mockito.ArgumentMatchers.anyLong());
    }

    private TokenResponse login() {
        return authService.login(new LoginRequest("user@example.com", "pw"));
    }

    private void assertInvalidRefreshToken(String refreshToken) {
        assertThatThrownBy(() -> authService.reissue(refreshToken))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    void 유효한_리프레시_토큰이면_새_토큰을_발급한다() {
        TokenResponse response = authService.reissue(login().refreshToken());

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
        assertInvalidRefreshToken(login().accessToken());
    }

    @Test
    void 사용자가_없으면_거부한다() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertInvalidRefreshToken(jwtProvider.createRefreshToken(USER_ID, 0, "sess", "jti"));
    }

    @Test
    void 저장되지_않은_리프레시_토큰은_거부한다() {
        assertInvalidRefreshToken(jwtProvider.createRefreshToken(USER_ID, 0, "sess", "jti"));
    }

    @Test
    void 재발급하면_새_리프레시_토큰이_이어서_쓰이고_같은_세션에_묶인다() {
        String first = login().refreshToken();

        String second = authService.reissue(first).refreshToken();
        String third = authService.reissue(second).refreshToken();

        assertThat(jwtProvider.getSessionId(second)).isEqualTo(jwtProvider.getSessionId(first));
        assertThat(jwtProvider.getSessionId(third)).isEqualTo(jwtProvider.getSessionId(first));
        assertThat(jwtProvider.getJti(second)).isNotEqualTo(jwtProvider.getJti(first));
    }

    @Test
    void 재발급에_쓴_리프레시_토큰으로_다시_재발급하면_거부한다() {
        String first = login().refreshToken();
        authService.reissue(first);

        assertInvalidRefreshToken(first);
    }

    @Test
    void 이미_쓴_토큰이_유예_시간_뒤에_다시_오면_그_세션의_토큰을_모두_폐기한다() {
        ReflectionTestUtils.setField(authService, "refreshReuseGraceSeconds", 0L);
        String first = login().refreshToken();
        String second = authService.reissue(first).refreshToken();

        assertInvalidRefreshToken(first);

        assertInvalidRefreshToken(second);
    }

    @Test
    void 방금_쓴_토큰이_유예_시간_안에_다시_오면_거절만_하고_세션은_유지한다() {
        String first = login().refreshToken();
        String second = authService.reissue(first).refreshToken();

        assertInvalidRefreshToken(first);

        // 먼저 간 요청이 받은 새 토큰은 계속 쓸 수 있다
        assertThat(authService.reissue(second)).isNotNull();
    }

    @Test
    void 쓰인_시각이_없는_기존_토큰이_다시_오면_유예_없이_재사용으로_본다() {
        String first = login().refreshToken();
        String second = authService.reissue(first).refreshToken();
        // 기존 행(used_at 없음)을 흉내 낸다
        ReflectionTestUtils.setField(stored.get(jwtProvider.getJti(first)), "usedAt", null);

        assertInvalidRefreshToken(first);

        assertInvalidRefreshToken(second);
    }

    @Test
    void 한_기기에서_로그아웃해도_다른_기기의_재발급은_성공한다() {
        TokenResponse deviceA = login();
        TokenResponse deviceB = login();

        authService.logout(jwtProvider.getSessionId(deviceA.accessToken()));

        assertInvalidRefreshToken(deviceA.refreshToken());
        assertThat(authService.reissue(deviceB.refreshToken())).isNotNull();
    }

    @Test
    void 비밀번호를_재설정하면_모든_기기에서_재발급할_수_없다() {
        TokenResponse deviceA = login();
        TokenResponse deviceB = login();

        authService.resetPassword(new ResetPasswordRequest(
                "user@example.com", "vrf_token", "newpassword1", "newpassword1"));

        assertInvalidRefreshToken(deviceA.refreshToken());
        assertInvalidRefreshToken(deviceB.refreshToken());
        assertThat(stored).isEmpty();
    }

    @Test
    void 가입은_이메일을_소문자로_정규화해_저장한다() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class))).thenAnswer(i -> i.getArgument(0));
        com.signal.domain.auth.dto.request.SignupRequest request = new com.signal.domain.auth.dto.request.SignupRequest(
                " User@Example.com ", "password1", "vrf_token", "사용자", 20, User.Gender.MALE,
                new com.signal.domain.auth.dto.request.SignupRequest.Agreements(true, true));

        authService.signup(request);

        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("user@example.com");
        org.mockito.Mockito.verify(verificationService).consumeToken("vrf_token", "user@example.com",
                VerificationService.Purpose.SIGNUP);
    }
}
