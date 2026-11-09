package com.signal.domain.user.repository;

import com.signal.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 인증 필터가 요청마다 쓰므로 엔티티 전체가 아니라 tokenVersion만 읽는다. 사용자가 없으면 비어 있다. */
    @Query("select u.tokenVersion from User u where u.id = :id")
    Optional<Integer> findTokenVersionById(@Param("id") Long id);

    /**
     * 인증 필터용. 사용자 존재·tokenVersion과 세션 생존을 한 번의 쿼리로 확인한다. 살아 있는 세션에는 아직 쓰이지 않은
     * 리프레시 토큰이 항상 하나 있으므로(회전은 쓰는 즉시 새 토큰을 저장한다), 쓰인 행만 남은 세션은 죽은 것으로 본다.
     * 사용자가 없거나 세션이 없으면 비어 있다.
     */
    @Query("select u.tokenVersion from User u where u.id = :id and exists "
            + "(select 1 from RefreshToken t where t.sessionId = :sessionId and t.used = false)")
    Optional<Integer> findTokenVersionByIdWithLiveSession(@Param("id") Long id, @Param("sessionId") String sessionId);
}
