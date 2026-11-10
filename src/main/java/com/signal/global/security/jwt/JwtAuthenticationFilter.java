package com.signal.global.security.jwt;

import com.signal.domain.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && jwtProvider.validateToken(token) && !jwtProvider.isRefreshToken(token)) {
            Long userId = jwtProvider.getUserId(token);

            String sessionId = jwtProvider.getSessionId(token);

            // 탈퇴한 사용자의 토큰, 비밀번호 재설정으로 tokenVersion이 올라간 뒤의 토큰, 로그아웃해 세션이 폐기된 뒤의
            // 토큰은 만료 전까지 서명이 유효하다. 사용자 존재·tokenVersion·세션 생존을 한 번의 쿼리로 확인해 거른다.
            Optional<Integer> tokenVersion = sessionId == null
                    ? Optional.empty()
                    : userRepository.findTokenVersionByIdWithLiveSession(userId, sessionId);
            tokenVersion
                    .filter(version -> version == jwtProvider.getTokenVersion(token))
                    .ifPresent(version -> {
                        var authentication = new UsernamePasswordAuthenticationToken(
                                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                        authentication.setDetails(sessionId);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearer) && bearer.startsWith(BEARER_PREFIX)) {
            return bearer.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
