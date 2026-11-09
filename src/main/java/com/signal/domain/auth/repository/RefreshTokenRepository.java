package com.signal.domain.auth.repository;

import com.signal.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    /** 아직 쓰이지 않은 토큰만 원자적으로 사용 처리한다. 반환값이 0이면 없거나 이미 쓰인 토큰이다. */
    @Modifying
    @Query("update RefreshToken t set t.used = true where t.jti = :jti and t.used = false")
    int markUsed(@Param("jti") String jti);

    boolean existsBySessionId(String sessionId);

    @Modifying
    @Query("delete from RefreshToken t where t.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") String sessionId);

    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
