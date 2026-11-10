package com.signal.domain.riskassessment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.service.RiskAssessmentService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 위험도 분석은 비회원도 X-Anonymous-Id로 쓸 수 있어 경로는 열려 있고, 소유권 검사는 서비스가 한다. */
@WebMvcTest(RiskAssessmentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class RiskAssessmentControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.signal.global.file.FileUrlSigner fileUrlSigner;

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
    private com.signal.domain.auth.repository.RefreshTokenRepository refreshTokenRepository;

    private RiskAssessment processing() {
        RiskAssessment assessment = RiskAssessment.processing(null, "anon-1", "/uploads/a.png");
        ReflectionTestUtils.setField(assessment, "id", 6L);
        return assessment;
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});
    }

    @Test
    void 비회원이_사진을_올리면_202와_PROCESSING을_반환한다() throws Exception {
        when(riskAssessmentService.createAssessment(isNull(), eq("anon-1"), any())).thenReturn(processing());

        mockMvc.perform(multipart("/api/v1/risk-assessments").file(image()).header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assessmentId").value(6))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.overallScore").doesNotExist());
    }

    @Test
    void 이미지_파트가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/v1/risk-assessments").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(riskAssessmentService);
    }

    @Test
    void 비회원_이용_횟수를_넘으면_429다() throws Exception {
        when(riskAssessmentService.createAssessment(any(), any(), any()))
                .thenThrow(new SignalException(ErrorCode.ANONYMOUS_RISK_ASSESSMENT_LIMIT_EXCEEDED));

        mockMvc.perform(multipart("/api/v1/risk-assessments").file(image()).header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ANONYMOUS_RISK_ASSESSMENT_LIMIT_EXCEEDED"));
    }

    @Test
    void 내_분석_목록을_반환한다() throws Exception {
        when(riskAssessmentService.getMyAssessments(1L)).thenReturn(List.of(processing()));

        mockMvc.perform(get("/api/v1/risk-assessments").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assessmentId").value(6));
    }

    @Test
    void 비로그인으로_목록을_요청하면_401이다() throws Exception {
        when(riskAssessmentService.getMyAssessments(null)).thenThrow(new SignalException(ErrorCode.UNAUTHORIZED));

        mockMvc.perform(get("/api/v1/risk-assessments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 분석_결과를_조회한다() throws Exception {
        when(riskAssessmentService.getAssessment(null, "anon-1", 6L)).thenReturn(processing());

        mockMvc.perform(get("/api/v1/risk-assessments/6").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentId").value(6));
    }

    @Test
    void 남의_분석이면_404다() throws Exception {
        when(riskAssessmentService.getAssessment(1L, null, 9L)).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/risk-assessments/9").with(USER_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
