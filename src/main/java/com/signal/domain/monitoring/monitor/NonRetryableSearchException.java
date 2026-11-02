package com.signal.domain.monitoring.monitor;

/** 다시 시도해도 소용없는 검색 실패(잘못된 요청, 키 거절, 읽을 수 없는 이미지). 연속 실패를 기다리지 않고 바로 멈춘다. */
public class NonRetryableSearchException extends RuntimeException {

    public NonRetryableSearchException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonRetryableSearchException(String message) {
        super(message);
    }
}
