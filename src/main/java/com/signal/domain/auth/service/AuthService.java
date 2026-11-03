package com.signal.domain.auth.service;

import com.signal.domain.auth.dto.request.LoginRequest;
import com.signal.domain.auth.dto.request.ResetPasswordRequest;
import com.signal.domain.auth.dto.request.SignupRequest;
import com.signal.domain.auth.dto.response.TokenResponse;
import com.signal.domain.auth.service.VerificationService.Purpose;
import com.signal.domain.user.entity.User;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.security.jwt.JwtProvider;
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

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new SignalException(ErrorCode.LOGIN_FAILED));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new SignalException(ErrorCode.LOGIN_FAILED);
        }

        return issueTokens(user);
    }

    @Transactional
    public Long signup(SignupRequest request) {
        if (!request.agreements().allAgreed()) {
            throw new SignalException(ErrorCode.AGREEMENT_REQUIRED);
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new SignalException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        verificationService.consumeToken(request.verificationToken(), request.email(), Purpose.SIGNUP);

        User user = User.builder()
                .email(request.email())
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

        verificationService.consumeToken(request.verificationToken(), request.email(), Purpose.PASSWORD_RESET);

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        user.updatePassword(passwordEncoder.encode(request.newPassword()));
        // 비밀번호를 바꾼 뒤에는 이전에 발급된 리프레시 토큰으로 재발급할 수 없어야 한다(탈취 대응).
        user.invalidateTokens();
    }

    public TokenResponse reissue(String refreshToken) {
        if (!jwtProvider.validateToken(refreshToken) || !jwtProvider.isRefreshToken(refreshToken)) {
            throw new SignalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(jwtProvider.getUserId(refreshToken))
                .orElseThrow(() -> new SignalException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (jwtProvider.getTokenVersion(refreshToken) != user.getTokenVersion()) {
            throw new SignalException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        return issueTokens(user);
    }

    @Transactional
    public void logout(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));
        user.invalidateTokens();
    }

    private TokenResponse issueTokens(User user) {
        return new TokenResponse(
                jwtProvider.createAccessToken(user.getId(), user.getTokenVersion()),
                jwtProvider.createRefreshToken(user.getId(), user.getTokenVersion()),
                accessTokenExpiration / 1000
        );
    }
}
