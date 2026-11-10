package com.signal.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signal.domain.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "valid.access.token";

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private UserRepository userRepository;

    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtProvider, userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication doFilterWithBearer(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        jwtAuthenticationFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void 유효한_액세스_토큰이고_사용자가_존재하면_인증을_설정한다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.isRefreshToken(TOKEN)).thenReturn(false);
        when(jwtProvider.getUserId(TOKEN)).thenReturn(1L);
        when(jwtProvider.getTokenVersion(TOKEN)).thenReturn(0);
        when(jwtProvider.getSessionId(TOKEN)).thenReturn("sess-1");
        when(userRepository.findTokenVersionByIdWithLiveSession(1L, "sess-1")).thenReturn(Optional.of(0));

        Authentication authentication = doFilterWithBearer(TOKEN);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getDetails()).isEqualTo("sess-1");
        assertThat(authentication.getPrincipal()).isEqualTo(1L);
    }

    @Test
    void 로그아웃으로_tokenVersion이_올라간_뒤의_옛_액세스_토큰으로는_인증하지_않는다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.isRefreshToken(TOKEN)).thenReturn(false);
        when(jwtProvider.getUserId(TOKEN)).thenReturn(1L);
        when(jwtProvider.getTokenVersion(TOKEN)).thenReturn(0);
        when(jwtProvider.getSessionId(TOKEN)).thenReturn("sess-1");
        when(userRepository.findTokenVersionByIdWithLiveSession(1L, "sess-1")).thenReturn(Optional.of(1));

        assertThat(doFilterWithBearer(TOKEN)).isNull();
    }

    @Test
    void 로그아웃으로_세션이_폐기된_뒤의_액세스_토큰으로는_인증하지_않는다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.isRefreshToken(TOKEN)).thenReturn(false);
        when(jwtProvider.getUserId(TOKEN)).thenReturn(1L);
        when(jwtProvider.getSessionId(TOKEN)).thenReturn("sess-1");
        when(userRepository.findTokenVersionByIdWithLiveSession(1L, "sess-1")).thenReturn(Optional.empty());

        assertThat(doFilterWithBearer(TOKEN)).isNull();
    }

    @Test
    void 탈퇴한_사용자의_토큰이면_인증하지_않는다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.isRefreshToken(TOKEN)).thenReturn(false);
        when(jwtProvider.getUserId(TOKEN)).thenReturn(99L);
        when(jwtProvider.getSessionId(TOKEN)).thenReturn("sess-1");
        when(userRepository.findTokenVersionByIdWithLiveSession(99L, "sess-1")).thenReturn(Optional.empty());

        assertThat(doFilterWithBearer(TOKEN)).isNull();
    }

    @Test
    void 세션_식별자가_없는_액세스_토큰으로는_조회_없이_인증하지_않는다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.isRefreshToken(TOKEN)).thenReturn(false);
        when(jwtProvider.getUserId(TOKEN)).thenReturn(1L);
        when(jwtProvider.getSessionId(TOKEN)).thenReturn(null);

        assertThat(doFilterWithBearer(TOKEN)).isNull();
        verify(userRepository, never()).findTokenVersionByIdWithLiveSession(anyLong(), any());
    }

    @Test
    void 리프레시_토큰으로는_인증하지_않는다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(true);
        when(jwtProvider.isRefreshToken(TOKEN)).thenReturn(true);

        assertThat(doFilterWithBearer(TOKEN)).isNull();
        verify(userRepository, never()).findTokenVersionByIdWithLiveSession(anyLong(), any());
    }

    @Test
    void 서명이_유효하지_않은_토큰으로는_인증하지_않는다() throws Exception {
        when(jwtProvider.validateToken(TOKEN)).thenReturn(false);

        assertThat(doFilterWithBearer(TOKEN)).isNull();
    }

    @Test
    void 토큰이_없으면_인증하지_않는다() throws Exception {
        assertThat(doFilterWithBearer(null)).isNull();
    }
}
