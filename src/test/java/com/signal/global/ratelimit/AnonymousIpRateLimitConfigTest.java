package com.signal.global.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.riskassessment.controller.RiskAssessmentController;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.service.RiskAssessmentService;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.file.FileUrlSigner;
import com.signal.global.security.JwtAccessDeniedHandler;
import com.signal.global.security.JwtAuthenticationEntryPoint;
import com.signal.global.security.jwt.JwtAuthenticationFilter;
import com.signal.global.security.jwt.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(RiskAssessmentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class, AnonymousIpRateLimitConfig.class})
@TestPropertySource(properties = {"cors.allowed-origins=http://localhost:3000",
        "anonymous.ip-rate-limit.max-requests=2"})
class AnonymousIpRateLimitConfigTest {

    private static final RequestPostProcessor USER_1 = authentication(new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RiskAssessmentService riskAssessmentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private FileUrlSigner fileUrlSigner;

    private void post(String anonymousId, RequestPostProcessor user, int expected) throws Exception {
        var request = multipart("/api/v1/risk-assessments")
                .file(new MockMultipartFile("image", "a.png", "image/png", new byte[] {1}))
                .header("X-Anonymous-Id", anonymousId);
        var result = mockMvc.perform(user == null ? request : request.with(user)).andExpect(status().is(expected));
        if (expected == 429) {
            result.andExpect(jsonPath("$.code").value("ANONYMOUS_IP_RATE_LIMIT_EXCEEDED"));
        }
    }

    @Test
    void 같은_IP가_익명_ID를_바꿔도_한도를_넘으면_429다_로그인_사용자는_영향이_없다() throws Exception {
        when(riskAssessmentService.createAssessment(any(), any(), any()))
                .thenReturn(RiskAssessment.processing(null, "a", "/uploads/a.png"));

        post("anon-1", null, 202);
        post("anon-2", null, 202);
        post("anon-3", null, 429);
        post("anon-4", null, 429);
        post("anon-5", USER_1, 202);
    }
}
