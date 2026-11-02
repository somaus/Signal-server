package com.signal.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일 크기가 초과되었습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 파일 형식입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // Auth
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 일치하지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관에 동의해야 합니다."),
    CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),
    INVALID_VERIFICATION(HttpStatus.UNAUTHORIZED, "인증 토큰이 유효하지 않습니다."),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    VERIFICATION_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "인증번호는 잠시 후 다시 요청할 수 있습니다."),

    // AI 분석
    FACE_NOT_DETECTED(HttpStatus.UNPROCESSABLE_ENTITY, "얼굴을 검출하지 못했습니다."),
    ANALYSIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 이미지를 분석하지 못했습니다. 잠시 후 다시 시도해주세요."),
    ANONYMOUS_ID_REQUIRED(HttpStatus.BAD_REQUEST, "비로그인 이용을 위해서는 익명 식별자(X-Anonymous-Id)가 필요합니다."),
    ANONYMOUS_DETECTION_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "비로그인 체험 가능 횟수를 초과했습니다. 로그인 후 이용해주세요."),
    ANONYMOUS_RISK_ASSESSMENT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "비로그인 체험 가능 횟수를 초과했습니다. 로그인 후 이용해주세요."),
    ANONYMOUS_CHAT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "비로그인 체험 가능 횟수를 초과했습니다. 로그인 후 이용해주세요."),

    // 이미지 보호
    PROTECTION_NOT_READY(HttpStatus.CONFLICT, "이미지 보호 처리가 아직 완료되지 않았습니다."),

    // 얼굴 도용 모니터링
    MONITORING_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "등록할 수 있는 모니터링 수를 초과했습니다. 기존 모니터링을 삭제한 뒤 다시 시도해주세요."),

    // 신고 문서
    REQUIRED_FIELD_MISSING(HttpStatus.BAD_REQUEST, "필수 항목이 누락되었습니다."),
    REPORT_ALREADY_FINALIZED(HttpStatus.CONFLICT, "이미 확정된 신고서는 수정할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
