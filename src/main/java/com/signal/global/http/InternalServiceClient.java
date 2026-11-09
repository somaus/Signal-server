package com.signal.global.http;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 같은 compose 안의 내부 추론 서비스(detect-lab, risk-lab 등)를 부르는 클라이언트들이 함께 쓰는 도우미.
 * 연결·읽기 제한 시간 설정, 파일 업로드 본문 만들기, 타임아웃 판별이 서비스마다 똑같아서 한 곳에 둔다.
 */
public final class InternalServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private InternalServiceClient() {
    }

    /** 전역 http.client.read-timeout(10초)은 첫 추론에 짧을 수 있어, 서비스별로 읽기 제한 시간을 따로 둔 클라이언트를 만든다. */
    public static RestClient create(RestClient.Builder builder, String baseUrl, long timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** multipart의 {@code file} 파트 하나짜리 본문. 파일 이름이 비어 있으면 {@code image}를 쓴다. */
    public static MultiValueMap<String, Object> fileBody(byte[] bytes, String filename) {
        String name = filename == null || filename.isBlank() ? "image" : filename;
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        return body;
    }

    /** 원인 사슬에 읽기·연결 타임아웃이 있으면 true. */
    public static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 업로드 URL(예: /uploads/x/abc.png)에서 파일 이름만 뽑는다. */
    public static String filenameOf(String url) {
        if (url == null || url.isBlank()) {
            return "image";
        }
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        return name.isBlank() ? "image" : name;
    }
}
