package com.signal.domain.auth.service;

import com.signal.domain.auth.dto.request.LoginRequest;
import com.signal.domain.auth.dto.request.ResetPasswordRequest;
import com.signal.domain.auth.dto.request.SignupRequest;
import com.signal.domain.auth.dto.response.TokenResponse;
import com.signal.domain.auth.service.VerificationService.Purpose;
import com.signal.domain.auth.entity.RefreshToken;
import com.signal.domain.auth.repository.RefreshTokenRepository;
import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.security.jwt.JwtProvider;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final VerificationService verificationService;
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalize(request.email()))
                .orElseThrow(() -> new SignalException(ErrorCode.LOGIN_FAILED));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SignalException(ErrorCode.LOGIN_FAILED);
        }

        return issueTokens(user, UUID.randomUUID().toString());
    }

    @Transactional
    public Long signup(SignupRequest request) {
        if (!request.agreements().allAgreed()) {
            throw new SignalException(ErrorCode.AGREEMENT_REQUIRED);
        }
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new SignalException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        verificationService.consumeToken(request.verificationToken(), email, Purpose.SIGNUP);

        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .age(request.age())
                .gender(request.gender())
                .build();

        return userRepository.save(user).getId();
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.newPasswordConfirm())) {
            throw new SignalException(ErrorCode.PASSWORD_MISMATCH);
        }

        String email = normalize(request.email());
        verificationService.consumeToken(request.verificationToken(), email, Purpose.PASSWORD_RESET);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        user.updatePassword(passwordEncoder.encode(request.newPassword()));
        // 비밀번호를 바꾼 뒤에는 이전에 발급된 리프레시 토큰으로 재발급할 수 없어야 한다(탈취 대응).
        user.invalidateTokens();
        refreshTokenRepository.deleteByUserId(user.getId());
    }

    // 재사용을 감지하면 세션을 폐기(삭제)한 뒤 예외를 던지므로, 예외가 그 삭제를 되돌리지 않게 한다.
    @Transactional(noRollbackFor = SignalException.class)
    public TokenResponse reissue(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken) || !jwtProvider.isRefreshToken(refreshToken)) {
            throw new SignalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(jwtProvider.getUserId(refreshToken))
                .orElseThrow(() -> new SignalException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (jwtProvider.getTokenVersion(refreshToken) != user.getTokenVersion()) {
            throw new SignalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        String jti = jwtProvider.getJti(refreshToken);
        String sessionId = jwtProvider.getSessionId(refreshToken);
        if (jti == null || sessionId == null) {
            throw new SignalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 회전: 한 번 쓴 토큰은 폐기된다. 이미 쓴 토큰이 다시 오면 탈취로 보고 그 세션의 토큰을 모두 폐기한다.
        if (refreshTokenRepository.markUsed(jti) == 0) {
            if (refreshTokenRepository.existsById(jti)) {
                refreshTokenRepository.deleteBySessionId(sessionId);
            }
            throw new SignalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        return issueTokens(user, sessionId);
    }

    @Transactional
    public void logout(String sessionId) {
        refreshTokenRepository.deleteBySessionId(sessionId);
    }

    /** 메일 주소는 대소문자·앞뒤 공백을 무시한다. 인증·가입·로그인이 같은 값으로 비교·저장되도록 진입 시점에 한 번 정규화한다. */
    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private TokenResponse issueTokens(User user, String sessionId) {
        // shortcut: 쓰인·만료된 토큰 행을 지우지 않는다, 행이 쌓여 부담이 되면 만료 정리 작업을 둔다.
        String jti = UUID.randomUUID().toString();
        refreshTokenRepository.save(new RefreshToken(jti, user.getId(), sessionId));
        return new TokenResponse(
                jwtProvider.createAccessToken(user.getId(), user.getTokenVersion(), sessionId),
                jwtProvider.createRefreshToken(user.getId(), user.getTokenVersion(), sessionId, jti),
                accessTokenExpiration / 1000
        );
    }
}
