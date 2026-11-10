package com.signal.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signal.global.exception.ErrorCode;
import com.signal.global.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/**
 * 시큐리티 필터 단계의 오류를 GlobalExceptionHandler와 같은 ErrorResponse 형태로 내려준다.
 * 필터에서 발생한 예외는 @ControllerAdvice까지 도달하지 않으므로 여기서 직접 직렬화한다.
 */
public final class SecurityErrorWriter {

    private SecurityErrorWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode errorCode)
            throws IOException {
        ErrorResponse body = ErrorResponse.of(
                errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage());

        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
