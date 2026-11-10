package com.signal.domain.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.report.dto.request.UpdateReportRequest;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportEvidence;
import com.signal.domain.report.service.ReportService;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.exception.SignalException;
import com.signal.global.security.JwtAccessDeniedHandler;
import com.signal.global.security.JwtAuthenticationEntryPoint;
import com.signal.global.security.jwt.JwtAuthenticationFilter;
import com.signal.global.security.jwt.JwtProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class ReportControllerTest {

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
    private ReportService reportService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private Report report() {
        Report report = Report.builder().userId(1L).incidentDate(LocalDate.of(2026, 1, 2))
                .description("피해 설명").sourceUrls(List.of("https://x.com/1")).evidenceIds(List.of()).build();
        ReflectionTestUtils.setField(report, "id", 3L);
        return report;
    }

    @Test
    void 인증_없이_신고서_API는_401이다() throws Exception {
        mockMvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reports/3")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reportService);
    }

    @Test
    void 신고서를_만들면_201과_DRAFT_상태를_반환한다() throws Exception {
        when(reportService.createReport(eq(1L), any())).thenReturn(report());

        mockMvc.perform(post("/api/v1/reports").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentDate\":\"2026-01-02\",\"description\":\"피해 설명\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId").value(3))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void 날짜_형식이_틀리면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/reports").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentDate\":\"2026/01/02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(reportService);
    }

    @Test
    void 필수_항목이_없으면_서비스_에러를_400으로_매핑한다() throws Exception {
        when(reportService.createReport(eq(1L), any()))
                .thenThrow(new SignalException(ErrorCode.REQUIRED_FIELD_MISSING));

        mockMvc.perform(post("/api/v1/reports").with(USER_1).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUIRED_FIELD_MISSING"));
    }

    @Test
    void 내_신고서_목록과_상세를_반환한다() throws Exception {
        when(reportService.getMyReports(1L)).thenReturn(List.of(report()));
        when(reportService.getReport(1L, 3L)).thenReturn(report());

        mockMvc.perform(get("/api/v1/reports").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reportId").value(3));
        mockMvc.perform(get("/api/v1/reports/3").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("피해 설명"))
                .andExpect(jsonPath("$.incidentDate").value("2026-01-02"))
                .andExpect(jsonPath("$.timeline[0].event").value("CREATED"));
    }

    @Test
    void 남의_신고서면_404다() throws Exception {
        when(reportService.getReport(1L, 9L)).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/reports/9").with(USER_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 신고서_ID가_숫자가_아니면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/reports/abc").with(USER_1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 신고서를_삭제하면_204다() throws Exception {
        mockMvc.perform(delete("/api/v1/reports/3").with(USER_1))
                .andExpect(status().isNoContent());
        verify(reportService).deleteReport(1L, 3L);
    }

    @Test
    void PATCH는_보내지_않은_필드와_null로_보낸_필드를_구분해_넘긴다() throws Exception {
        when(reportService.updateReport(eq(1L), eq(3L), any())).thenReturn(report());

        mockMvc.perform(patch("/api/v1/reports/3").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"damageType\":\"합성\",\"additionalNotes\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(3));

        ArgumentCaptor<UpdateReportRequest> captor = ArgumentCaptor.forClass(UpdateReportRequest.class);
        verify(reportService).updateReport(eq(1L), eq(3L), captor.capture());
        assertThat(captor.getValue().getDamageType()).contains("합성");
        assertThat(captor.getValue().getAdditionalNotes()).isEmpty();
        assertThat(captor.getValue().getDescription()).isNull();
    }

    @Test
    void 확정된_신고서를_수정하면_409다() throws Exception {
        when(reportService.updateReport(anyLong(), anyLong(), any()))
                .thenThrow(new SignalException(ErrorCode.REPORT_ALREADY_FINALIZED));

        mockMvc.perform(patch("/api/v1/reports/3").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"damageType\":\"합성\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_ALREADY_FINALIZED"));
    }

    @Test
    void 확정하면_상태를_반환한다() throws Exception {
        when(reportService.finalizeReport(1L, 3L)).thenReturn(report());

        mockMvc.perform(post("/api/v1/reports/3/finalize").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(3));
    }

    @Test
    void 증거_파일을_올리면_201과_URL을_반환한다() throws Exception {
        ReportEvidence evidence = ReportEvidence.builder().userId(1L).fileUrl("/uploads/e.png").build();
        ReflectionTestUtils.setField(evidence, "id", 8L);
        when(reportService.uploadEvidence(eq(1L), any())).thenReturn(evidence);

        mockMvc.perform(multipart("/api/v1/reports/evidence")
                        .file(new MockMultipartFile("file", "e.png", "image/png", new byte[] {1})).with(USER_1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidenceId").value(8))
                .andExpect(jsonPath("$.fileUrl").value("/uploads/e.png?exp=1&sig=s"));
    }

    @Test
    void 증거_파일_파트가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/v1/reports/evidence").with(USER_1))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reportService);
    }
}
