package com.signal.domain.monitoring.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.monitoring.entity.Monitoring;
import com.signal.domain.monitoring.service.MonitoringService;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(MonitoringController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class MonitoringControllerTest {

    private static final RequestPostProcessor USER_1 = authentication(new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitoringService monitoringService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private Monitoring monitoring() {
        Monitoring monitoring = Monitoring.builder().userId(1L).referenceImageUrl("/uploads/r.png").build();
        ReflectionTestUtils.setField(monitoring, "id", 4L);
        return monitoring;
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("referenceImage", "r.png", "image/png", new byte[] {1});
    }

    @Test
    void 인증_없이_모니터링_API는_401이다() throws Exception {
        mockMvc.perform(multipart("/api/v1/monitorings").file(image())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/monitorings/4")).andExpect(status().isUnauthorized());
        verifyNoInteractions(monitoringService);
    }

    @Test
    void 모니터링을_시작하면_201과_ACTIVE를_반환한다() throws Exception {
        when(monitoringService.createMonitoring(eq(1L), any())).thenReturn(monitoring());

        mockMvc.perform(multipart("/api/v1/monitorings").file(image()).with(USER_1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.monitoringId").value(4))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 기준_이미지_파트가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/v1/monitorings").with(USER_1))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(monitoringService);
    }

    @Test
    void 등록_한도를_넘으면_429다() throws Exception {
        when(monitoringService.createMonitoring(eq(1L), any()))
                .thenThrow(new SignalException(ErrorCode.MONITORING_LIMIT_EXCEEDED));

        mockMvc.perform(multipart("/api/v1/monitorings").file(image()).with(USER_1))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MONITORING_LIMIT_EXCEEDED"));
    }

    @Test
    void 모니터링_상태를_조회한다() throws Exception {
        when(monitoringService.getMyMonitoring(1L, 4L)).thenReturn(monitoring());

        mockMvc.perform(get("/api/v1/monitorings/4").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nextSearchAt").exists());
    }

    @Test
    void 남의_모니터링이면_404다() throws Exception {
        when(monitoringService.getMyMonitoring(1L, 9L)).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/monitorings/9").with(USER_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 탐지_결과는_페이지_파라미터를_넘기고_페이지_형태로_반환한다() throws Exception {
        when(monitoringService.getDetections(eq(1L), eq(4L), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/monitorings/4/detections").param("page", "2").param("size", "5").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(monitoringService).getDetections(eq(1L), eq(4L), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void 모니터링을_삭제하면_204다() throws Exception {
        mockMvc.perform(delete("/api/v1/monitorings/4").with(USER_1))
                .andExpect(status().isNoContent());
        verify(monitoringService).deleteMonitoring(1L, 4L);
    }
}
