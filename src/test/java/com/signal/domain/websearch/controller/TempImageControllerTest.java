package com.signal.domain.websearch.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.user.repository.UserRepository;
import com.signal.domain.websearch.client.TempImageStore;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.GlobalExceptionHandler;
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

/** 외부 검색 서비스가 가져가는 임시 이미지는 인증 없이 토큰으로만 받을 수 있고 캐시되지 않아야 한다. */
@WebMvcTest(TempImageController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class TempImageControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.signal.global.file.FileUrlSigner fileUrlSigner;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TempImageStore tempImageStore;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void 토큰이_있으면_인증_없이_이미지를_no_store로_반환한다() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(tempImageStore.get("tok")).thenReturn(new TempImageStore.Entry(bytes, "image/png"));

        mockMvc.perform(get("/api/v1/web-search/temp-images/tok"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void 없거나_만료된_토큰이면_404다() throws Exception {
        when(tempImageStore.get("gone")).thenReturn(null);

        mockMvc.perform(get("/api/v1/web-search/temp-images/gone"))
                .andExpect(status().isNotFound());
    }
}
