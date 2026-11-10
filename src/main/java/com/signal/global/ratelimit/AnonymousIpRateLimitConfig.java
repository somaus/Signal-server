package com.signal.global.ratelimit;

import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 외부 API 비용이 드는 비로그인 쓰기 요청(탐지, 위험도 분석, 상담 세션 생성·메시지, 웹 검색)과 인증 메일 발송에 IP 제한을 건다.
 * 인증 메일은 분석 한도와 섞이지 않게 따로 세고, 한 사람이 가입·재설정으로 보낼 일이 적어 한도를 더 낮게 둔다.
 * 로그인은 횟수 정책이 달라 여기서 다루지 않는다.
 */
@Configuration
public class AnonymousIpRateLimitConfig implements WebMvcConfigurer {

    private static final String VERIFICATION_SEND_PATH = "/api/v1/auth/verification/send";

    private final AnonymousIpRateLimiter limiter;
    private final AnonymousIpRateLimiter verificationLimiter;

    public AnonymousIpRateLimitConfig(
            @Value("${anonymous.ip-rate-limit.max-requests:30}") int maxRequests,
            @Value("${anonymous.ip-rate-limit.verification-max-requests:10}") int verificationMaxRequests,
            @Value("${anonymous.ip-rate-limit.window:1h}") Duration window) {
        this.limiter = new AnonymousIpRateLimiter(maxRequests, window, System::currentTimeMillis);
        this.verificationLimiter =
                new AnonymousIpRateLimiter(verificationMaxRequests, window, System::currentTimeMillis);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!HttpMethod.POST.matches(request.getMethod())) {
                    return true;
                }
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                boolean anonymous = auth == null || auth instanceof AnonymousAuthenticationToken;
                // getRemoteAddr만 쓴다. 프록시 뒤라면 server.forward-headers-strategy를 켜야 실제 클라이언트 IP가 되며,
                // X-Forwarded-For를 직접 읽으면 클라이언트가 위조해 제한을 피한다. 운영 서버에서 이 값이 클라이언트마다
                // 다른지 확인하지 않으면 모든 비로그인 사용자가 한 칸을 같이 쓴다(DEPLOY.md의 비로그인 IP 제한 참고).
                AnonymousIpRateLimiter target =
                        VERIFICATION_SEND_PATH.equals(request.getRequestURI()) ? verificationLimiter : limiter;
                if (anonymous && !target.tryAcquire(request.getRemoteAddr())) {
                    throw new SignalException(ErrorCode.ANONYMOUS_IP_RATE_LIMIT_EXCEEDED);
                }
                return true;
            }
        }).addPathPatterns(
                "/api/v1/deepfake-detections", "/api/v1/deepfake-detections/*/web-search",
                "/api/v1/risk-assessments", "/api/v1/chat/sessions", "/api/v1/chat/sessions/*/messages",
                VERIFICATION_SEND_PATH);
    }
}
