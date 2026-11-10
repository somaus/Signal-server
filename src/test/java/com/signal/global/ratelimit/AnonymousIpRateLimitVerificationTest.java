package com.signal.global.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.auth.controller.AuthController;
import com.signal.domain.auth.service.AuthService;
import com.signal.domain.auth.service.VerificationService;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.file.FileUrlSigner;
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

/** 인증 메일 발송은 분석 한도와 따로, 더 낮은 한도로 IP 기준 제한을 받는다. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class, AnonymousIpRateLimitConfig.class})
@TestPropertySource(properties = {"cors.allowed-origins=http://localhost:3000",
        "anonymous.ip-rate-limit.max-requests=100",
        "anonymous.ip-rate-limit.verification-max-requests=2"})
class AnonymousIpRateLimitVerificationTest {

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
    private FileUrlSigner fileUrlSigner;

    private void sendCode(String email, int expected) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/verification/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"purpose\":\"SIGNUP\"}"))
                .andExpect(status().is(expected));
        if (expected == 429) {
            result.andExpect(jsonPath("$.code").value("ANONYMOUS_IP_RATE_LIMIT_EXCEEDED"));
        }
    }

    @Test
    void 같은_IP가_주소를_바꿔도_인증_메일_한도를_넘으면_429다() throws Exception {
        sendCode("a@example.com", 200);
        sendCode("b@example.com", 200);
        sendCode("c@example.com", 429);
    }
}
