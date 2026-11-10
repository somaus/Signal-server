package com.signal.domain.protection.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.protection.entity.Protection;
import com.signal.domain.protection.entity.ProtectionLevel;
import com.signal.domain.protection.service.ProtectionService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ProtectionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class ProtectionControllerTest {

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
    private ProtectionService protectionService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private Protection protection() {
        Protection protection = Protection.builder().userId(1L).assessmentId(2L)
                .protectionLevel(ProtectionLevel.NORMAL).originalImageUrl("/uploads/o.png").build();
        ReflectionTestUtils.setField(protection, "id", 5L);
        return protection;
    }

    @Test
    void 인증_없이_보호_API는_401이다() throws Exception {
        mockMvc.perform(post("/api/v1/protections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":2}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/protections/5")).andExpect(status().isUnauthorized());
        verifyNoInteractions(protectionService);
    }

    @Test
    void 보호_요청은_202와_PROCESSING을_반환한다() throws Exception {
        when(protectionService.createProtection(1L, 2L, ProtectionLevel.NORMAL)).thenReturn(protection());

        mockMvc.perform(post("/api/v1/protections").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":2,\"protectionLevel\":\"NORMAL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.protectionId").value(5))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    void assessmentId가_없거나_보호_강도가_잘못되면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/protections").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"protectionLevel\":\"NORMAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(post("/api/v1/protections").with(USER_1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessmentId\":2,\"protectionLevel\":\"MAX\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(protectionService);
    }

    @Test
    void 보호_결과를_조회한다() throws Exception {
        when(protectionService.getProtection(1L, 5L)).thenReturn(protection());

        mockMvc.perform(get("/api/v1/protections/5").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalImageUrl").value("/uploads/o.png?exp=1&sig=s"))
                .andExpect(jsonPath("$.simulated").value(false));
    }

    @Test
    void 남의_보호_건이면_404다() throws Exception {
        when(protectionService.getProtection(1L, 9L)).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/protections/9").with(USER_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 완료된_보호_이미지는_PNG로_내려받는다() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        when(protectionService.downloadProtectedImage(1L, 5L)).thenReturn(png);

        mockMvc.perform(get("/api/v1/protections/5/download").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
    }

    @Test
    void 처리_전_다운로드는_409_PROTECTION_NOT_READY다() throws Exception {
        when(protectionService.downloadProtectedImage(1L, 5L))
                .thenThrow(new SignalException(ErrorCode.PROTECTION_NOT_READY));

        mockMvc.perform(get("/api/v1/protections/5/download").with(USER_1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROTECTION_NOT_READY"));
    }
}
