package com.signal.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.agency.controller.AgencyController;
import com.signal.domain.agency.entity.AgencySituationType;
import com.signal.global.response.ErrorResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    void 요청_본문_파싱에_실패하면_400_INVALID_INPUT을_반환한다() {
        HttpMessageNotReadableException exception =
                new HttpMessageNotReadableException("파싱 실패", (HttpInputMessage) null);

        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleHttpMessageNotReadableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_INPUT.name());
    }

    @Test
    void 쿼리파라미터_타입변환에_실패하면_400_INVALID_INPUT을_반환한다() throws NoSuchMethodException {
        Method method = AgencyController.class.getMethod("getAgencies", AgencySituationType.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "FOO", AgencySituationType.class, "situationType", methodParameter,
                new IllegalArgumentException("No enum constant"));

        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleMethodArgumentTypeMismatchException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_INPUT.name());
    }

    @Test
    void 필수_파일_파트가_없으면_500이_아니라_400_INVALID_INPUT을_반환한다() {
        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleMissingRequestValue(new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_INPUT.name());
    }

    @Test
    void 지원하지_않는_HTTP_메서드는_500이_아니라_405를_반환한다() {
        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.name());
    }

    @Test
    void 없는_경로는_500이_아니라_404를_반환한다() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/nothing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.NOT_FOUND.name());
    }

    @Test
    void 지원하지_않는_Content_Type은_500이_아니라_415를_반환한다() {
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("text/plain"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE.name());
    }
}
