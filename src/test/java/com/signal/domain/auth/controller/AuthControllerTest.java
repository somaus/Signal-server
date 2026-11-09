package com.signal.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.auth.dto.response.TokenResponse;
import com.signal.domain.auth.service.AuthService;
import com.signal.domain.auth.service.VerificationService;
import com.signal.domain.auth.service.VerificationService.Purpose;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.exception.SignalException;
import com.signal.global.security.JwtAccessDeniedHandler;
import com.signal.global.security.JwtAuthenticationEntryPoint;
import com.signal.global.security.jwt.JwtAuthenticationFilter;
import com.signal.global.security.jwt.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** reissue 경로는 AuthControllerReissueTest에서 다룬다. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class AuthControllerTest {

    private static final String SIGNUP_BODY = """
            {"email":"a@b.com","password":"password1","verificationToken":"vt","name":"홍길동",
             "age":30,"gender":"MALE","agreements":{"termsOfService":true,"privacyPolicy":true}}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private VerificationService verificationService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private com.signal.domain.auth.repository.RefreshTokenRepository refreshTokenRepository;

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 로그인에_성공하면_토큰을_반환한다() throws Exception {
        when(authService.login(any(), any())).thenReturn(new TokenResponse("at", "rt", 3600L));

        postJson("/api/v1/auth/login", "{\"email\":\"a@b.com\",\"password\":\"pw\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("at"))
                .andExpect(jsonPath("$.refreshToken").value("rt"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(3600));
    }

    @Test
    void 로그인할_때_익명_식별자_헤더를_서비스에_넘긴다() throws Exception {
        when(authService.login(any(), any())).thenReturn(new TokenResponse("at", "rt", 3600L));

        mockMvc.perform(post("/api/v1/auth/login").header("X-Anonymous-Id", "anon-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"pw\"}"))
                .andExpect(status().isOk());

        verify(authService).login(any(), org.mockito.ArgumentMatchers.eq("anon-1"));
    }

    @Test
    void 로그인_이메일_형식이_틀리거나_비밀번호가_없으면_400이다() throws Exception {
        postJson("/api/v1/auth/login", "{\"email\":\"not-email\",\"password\":\"pw\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        postJson("/api/v1/auth/login", "{\"email\":\"a@b.com\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void 로그인_실패는_401_LOGIN_FAILED다() throws Exception {
        when(authService.login(any(), any())).thenThrow(new SignalException(ErrorCode.LOGIN_FAILED));

        postJson("/api/v1/auth/login", "{\"email\":\"a@b.com\",\"password\":\"pw\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    void 회원가입에_성공하면_201로_userId와_email을_반환한다() throws Exception {
        when(authService.signup(any())).thenReturn(5L);

        postJson("/api/v1/auth/signup", SIGNUP_BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(5))
                .andExpect(jsonPath("$.email").value("a@b.com"));
    }

    @Test
    void 회원가입_비밀번호가_짧거나_약관이_없으면_400이다() throws Exception {
        postJson("/api/v1/auth/signup", SIGNUP_BODY.replace("password1", "short"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/auth/signup", SIGNUP_BODY.replace(
                ",\"agreements\":{\"termsOfService\":true,\"privacyPolicy\":true}", ""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void 이미_가입된_이메일이면_409다() throws Exception {
        when(authService.signup(any())).thenThrow(new SignalException(ErrorCode.EMAIL_ALREADY_EXISTS));

        postJson("/api/v1/auth/signup", SIGNUP_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void 인증번호_발송은_만료_시간을_반환한다() throws Exception {
        when(verificationService.sendCode("a@b.com", Purpose.SIGNUP)).thenReturn(300L);

        postJson("/api/v1/auth/verification/send", "{\"email\":\"a@b.com\",\"purpose\":\"SIGNUP\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(300));
    }

    @Test
    void 인증번호_발송_목적이_없거나_잘못되면_400이다() throws Exception {
        postJson("/api/v1/auth/verification/send", "{\"email\":\"a@b.com\"}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/auth/verification/send", "{\"email\":\"a@b.com\",\"purpose\":\"NOPE\"}")
                .andExpect(status().isBadRequest());
        verifyNoInteractions(verificationService);
    }

    @Test
    void 인증번호_확인에_성공하면_verificationToken을_반환한다() throws Exception {
        when(verificationService.verifyCode("a@b.com", "123456", Purpose.PASSWORD_RESET)).thenReturn("vt");

        postJson("/api/v1/auth/verification/verify",
                "{\"email\":\"a@b.com\",\"code\":\"123456\",\"purpose\":\"PASSWORD_RESET\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationToken").value("vt"));
    }

    @Test
    void 인증번호가_틀리면_400_CODE_MISMATCH다() throws Exception {
        when(verificationService.verifyCode(any(), any(), any()))
                .thenThrow(new SignalException(ErrorCode.CODE_MISMATCH));

        postJson("/api/v1/auth/verification/verify",
                "{\"email\":\"a@b.com\",\"code\":\"000000\",\"purpose\":\"SIGNUP\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_MISMATCH"));
    }

    @Test
    void 비밀번호_재설정에_성공하면_200이다() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"verificationToken\":\"vt\","
                                + "\"newPassword\":\"password1\",\"newPasswordConfirm\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
        verify(authService).resetPassword(any());
    }

    @Test
    void 비밀번호_재설정_토큰이_없으면_400이다() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"newPassword\":\"password1\",\"newPasswordConfirm\":\"password1\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void 로그아웃은_현재_세션을_폐기하고_204다() throws Exception {
        var authentication = new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails("sess-1");
        mockMvc.perform(post("/api/v1/auth/logout").with(authentication(authentication)))
                .andExpect(status().isNoContent());
        verify(authService).logout("sess-1");
    }

    @Test
    void 인증_없이_로그아웃하면_401이다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(authService);
    }
}
