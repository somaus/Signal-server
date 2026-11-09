package com.signal.domain.agency.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.agency.entity.Agency;
import com.signal.domain.agency.entity.AgencyConnection;
import com.signal.domain.agency.entity.AgencySituationType;
import com.signal.domain.agency.entity.ConnectionType;
import com.signal.domain.agency.service.AgencyService;
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

@WebMvcTest(AgencyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class AgencyControllerTest {

    private static final RequestPostProcessor USER_1 = authentication(new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgencyService agencyService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void 기관_목록은_인증_없이_조회되고_상황_유형으로_거른다() throws Exception {
        Agency agency = Agency.builder().name("테스트기관").phone("1234").website("https://a.kr")
                .supportedActions(List.of()).supportedSituationTypes(List.of(AgencySituationType.CRISIS)).build();
        ReflectionTestUtils.setField(agency, "id", 7L);
        when(agencyService.getAgencies(AgencySituationType.CRISIS)).thenReturn(List.of(agency));

        mockMvc.perform(get("/api/v1/agencies").param("situationType", "CRISIS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agencies[0].agencyId").value(7))
                .andExpect(jsonPath("$.agencies[0].phone").value("1234"));
    }

    @Test
    void 알_수_없는_상황_유형이면_400이다() throws Exception {
        mockMvc.perform(get("/api/v1/agencies").param("situationType", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 기관_연결은_201로_연결_정보를_반환한다() throws Exception {
        AgencyConnection connection = AgencyConnection.builder().userId(1L).agencyId(3L)
                .connectionType(ConnectionType.PHONE).agencyName("기관").phone("1234").build();
        ReflectionTestUtils.setField(connection, "id", 10L);
        when(agencyService.createConnection(eq(1L), eq(3L), isNull(), eq(ConnectionType.PHONE)))
                .thenReturn(connection);

        mockMvc.perform(post("/api/v1/agencies/3/connections").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connectionType\":\"PHONE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connectionId").value(10))
                .andExpect(jsonPath("$.connectionType").value("PHONE"))
                .andExpect(jsonPath("$.phone").value("1234"));
    }

    @Test
    void 기관_연결은_인증이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/v1/agencies/3/connections")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connectionType\":\"PHONE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 연결_유형이_잘못된_값이면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/agencies/3/connections").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"connectionType\":\"FAX\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 없는_기관이나_남의_신고서면_404다() throws Exception {
        when(agencyService.createConnection(any(), any(), any(), any()))
                .thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(post("/api/v1/agencies/99/connections").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reportId\":5,\"connectionType\":\"WEB\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(agencyService).createConnection(1L, 99L, 5L, ConnectionType.WEB);
    }
}
