package com.signal.domain.deepfakedetection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.service.DeepfakeDetectionService;
import com.signal.domain.user.repository.UserRepository;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.service.WebSearchService;
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

/** 웹 검색 꺼짐/NOT_FOUND 구분은 DeepfakeDetectionControllerWebSearchTest에서 다룬다. */
@WebMvcTest(DeepfakeDetectionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class DeepfakeDetectionControllerTest {

    private static final RequestPostProcessor USER_1 = authentication(new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.signal.global.file.FileUrlSigner fileUrlSigner;

    @org.junit.jupiter.api.BeforeEach
    void signUrls() {
        when(fileUrlSigner.sign(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> i.getArgument(0, String.class) + "?exp=1&sig=s");
    }

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

    private DeepfakeDetection detection(String fileUrl) {
        DeepfakeDetection detection = DeepfakeDetection.builder().anonymousId("anon-1").fileUrl(fileUrl).build();
        ReflectionTestUtils.setField(detection, "id", 3L);
        return detection;
    }

    @Test
    void 파일을_올리면_202와_PROCESSING을_반환한다() throws Exception {
        when(deepfakeDetectionService.createDetection(isNull(), eq("anon-1"), any()))
                .thenReturn(detection("/uploads/a.png"));

        mockMvc.perform(multipart("/api/v1/deepfake-detections")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}))
                        .header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.detectionId").value(3))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void 파일_파트가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/v1/deepfake-detections").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(deepfakeDetectionService);
    }

    @Test
    void 비회원_이용_횟수를_넘으면_429다() throws Exception {
        when(deepfakeDetectionService.createDetection(any(), any(), any()))
                .thenThrow(new SignalException(ErrorCode.ANONYMOUS_DETECTION_LIMIT_EXCEEDED));

        mockMvc.perform(multipart("/api/v1/deepfake-detections")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}))
                        .header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ANONYMOUS_DETECTION_LIMIT_EXCEEDED"));
    }

    @Test
    void 탐지_결과에_웹_검색_가능_여부와_결과를_함께_내린다() throws Exception {
        when(deepfakeDetectionService.getDetection(1L, null, 3L)).thenReturn(detection("/uploads/a.png"));
        when(webSearchService.find(3L)).thenReturn(WebSearch.processing(3L, "u:1"));
        when(webSearchService.isEnabled()).thenReturn(true);

        mockMvc.perform(get("/api/v1/deepfake-detections/3").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectionId").value(3))
                .andExpect(jsonPath("$.webSearch.status").value("PROCESSING"))
                .andExpect(jsonPath("$.webSearchAvailable").value(true));
    }

    @Test
    void 남의_탐지_결과면_404다() throws Exception {
        when(deepfakeDetectionService.getDetection(1L, null, 9L)).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/deepfake-detections/9").with(USER_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 로그인_사용자의_웹_검색은_u_키로_시작하고_202다() throws Exception {
        when(webSearchService.isEnabled()).thenReturn(true);
        when(deepfakeDetectionService.getDetection(1L, null, 3L)).thenReturn(detection("/uploads/a.png"));
        when(webSearchService.find(3L)).thenReturn(WebSearch.processing(3L, "u:1"));

        mockMvc.perform(post("/api/v1/deepfake-detections/3/web-search").with(USER_1))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.matchCount").value(0));
        verify(webSearchService).start(3L, "/uploads/a.png", false, "u:1");
    }

    @Test
    void 비회원의_영상_웹_검색은_a_키와_영상_표시로_시작한다() throws Exception {
        when(webSearchService.isEnabled()).thenReturn(true);
        when(deepfakeDetectionService.getDetection(null, "anon-1", 3L)).thenReturn(detection("/uploads/V.MP4"));
        when(webSearchService.find(3L)).thenReturn(WebSearch.processing(3L, "a:anon-1"));

        mockMvc.perform(post("/api/v1/deepfake-detections/3/web-search").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isAccepted());
        verify(webSearchService).start(3L, "/uploads/V.MP4", true, "a:anon-1");
    }

    @Test
    void 로그인도_익명_ID도_없이_웹_검색하면_400이다() throws Exception {
        when(webSearchService.isEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/v1/deepfake-detections/3/web-search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANONYMOUS_ID_REQUIRED"));
        verifyNoInteractions(deepfakeDetectionService);
    }
}
