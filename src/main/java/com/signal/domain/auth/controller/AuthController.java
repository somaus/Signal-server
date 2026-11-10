package com.signal.domain.auth.controller;

import com.signal.domain.auth.dto.request.LoginRequest;
import com.signal.domain.auth.dto.request.ReissueRequest;
import com.signal.domain.auth.dto.request.ResetPasswordRequest;
import com.signal.domain.auth.dto.request.SendVerificationRequest;
import com.signal.domain.auth.dto.request.SignupRequest;
import com.signal.domain.auth.dto.request.VerifyCodeRequest;
import com.signal.domain.auth.dto.response.TokenResponse;
import com.signal.domain.auth.service.AuthService;
import com.signal.domain.auth.service.VerificationService;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final VerificationService verificationService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @RequestHeader(value = "X-Anonymous-Id", required = false) String anonymousId,
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request, anonymousId));
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody SignupRequest request) {
        Long userId = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("userId", userId, "email", request.email()));
    }

    /** 인증번호 발송 (회원가입 / 비밀번호 찾기 공용) */
    @PostMapping("/verification/send")
    public ResponseEntity<Map<String, Long>> sendVerification(
            @Valid @RequestBody SendVerificationRequest request) {
        long expiresIn = verificationService.sendCode(request.email(), request.purpose());
        return ResponseEntity.ok(Map.of("expiresIn", expiresIn));
    }

    /** 인증번호 확인 — 성공 시 verificationToken 반환 → 프론트는 재설정 화면으로 이동 */
    @PostMapping("/verification/verify")
    public ResponseEntity<Map<String, String>> verifyCode(
            @Valid @RequestBody VerifyCodeRequest request) {
        String token = verificationService.verifyCode(request.email(), request.code(), request.purpose());
        return ResponseEntity.ok(Map.of("verificationToken", token));
    }

    /** 비밀번호 재설정 — verificationToken 필요 */
    @PatchMapping("/password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."));
    }

    @PatchMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ResponseEntity.ok(authService.reissue(request.refreshToken()));
    }

    /** 로그아웃 — 이 기기(세션)의 리프레시·액세스 토큰만 무효화한다. 다른 기기의 로그인은 유지된다. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long userId, Authentication authentication) {
        if (userId == null) {
            throw new SignalException(ErrorCode.UNAUTHORIZED);
        }
        authService.logout((String) authentication.getDetails());
        return ResponseEntity.noContent().build();
    }
}
