package com.signal.domain.deepfakedetection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.deepfakedetection.service.DeepfakeDetectionService;
import com.signal.domain.user.repository.UserRepository;
import com.signal.domain.websearch.service.WebSearchService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 웹 검색이 꺼진 경우(WEB_SEARCH_DISABLED)와 탐지 결과가 없는 경우(NOT_FOUND)는 둘 다 404지만 code로 구분되어야 한다.
 * 프론트가 이 code로 "준비 중"과 "결과 없음"을 가른다.
 */
@WebMvcTest(DeepfakeDetectionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class DeepfakeDetectionControllerWebSearchTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeepfakeDetectionService deepfakeDetectionService;

    @MockitoBean
    private WebSearchService webSearchService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private com.signal.domain.auth.repository.RefreshTokenRepository refreshTokenRepository;

    @Test
    void 웹_검색이_꺼져_있으면_404_WEB_SEARCH_DISABLED다() throws Exception {
        when(webSearchService.isEnabled()).thenReturn(false);

        mockMvc.perform(post("/api/v1/deepfake-detections/1/web-search").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.WEB_SEARCH_DISABLED.name()));
    }

    @Test
    void 켜져_있는데_탐지_결과가_없으면_404_NOT_FOUND다() throws Exception {
        when(webSearchService.isEnabled()).thenReturn(true);
        when(deepfakeDetectionService.getDetection(any(), any(), anyLong()))
                .thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(post("/api/v1/deepfake-detections/1/web-search").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.name()));
    }
}
