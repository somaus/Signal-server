package com.signal.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 발급한 리프레시 토큰 한 장. 같은 로그인(기기)에서 회전으로 이어진 토큰은 sessionId를 공유한다. */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @Column(length = 36)
    private String jti;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 36)
    private String sessionId;

    /** 재발급에 한 번 쓰였으면 true. 다시 오면 재사용(탈취 의심)이다. */
    @Column(nullable = false)
    private boolean used;

    /** 재발급에 쓰인 시각. 쓰이지 않았거나 이 컬럼이 생기기 전에 쓰인 토큰은 null이다. */
    private LocalDateTime usedAt;

    public RefreshToken(String jti, Long userId, String sessionId) {
        this.jti = jti;
        this.userId = userId;
        this.sessionId = sessionId;
    }
}
