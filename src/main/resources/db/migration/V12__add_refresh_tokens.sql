-- 리프레시 토큰 회전·재사용 감지와 기기(세션)별 로그아웃을 위해 발급한 토큰을 기록한다.
CREATE TABLE refresh_tokens (
    jti        VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    INDEX idx_refresh_tokens_session (session_id),
    INDEX idx_refresh_tokens_user (user_id)
);
