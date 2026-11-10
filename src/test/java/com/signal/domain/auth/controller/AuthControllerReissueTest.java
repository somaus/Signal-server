package com.signal.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.auth.service.AuthService;
import com.signal.domain.auth.service.VerificationService;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.exception.SignalException;
import com.signal.global.security.JwtAccessDeniedHandler;
import com.signal.global.security.JwtAuthenticationEntryPoint;
import com.signal.global.security.jwt.JwtAuthenticationFilter;
import com.signal.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /reissue는 refreshToken이 없거나 비어 있어도 500이 아니라 INVALID_REFRESH_TOKEN(401)으로 응답해야 한다. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class AuthControllerReissueTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.signal.global.file.FileUrlSigner fileUrlSigner;

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

    private void givenReissueRejects() {
        when(authService.reissue(any())).thenThrow(new SignalException(ErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    void refreshToken_없이_요청하면_401_INVALID_REFRESH_TOKEN이다() throws Exception {
        givenReissueRejects();

        mockMvc.perform(patch("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshToken이_빈_문자열이면_401_INVALID_REFRESH_TOKEN이다() throws Exception {
        givenReissueRejects();

        mockMvc.perform(patch("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 본문이_JSON이_아니면_400이다() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }
}
