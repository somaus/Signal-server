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
}
