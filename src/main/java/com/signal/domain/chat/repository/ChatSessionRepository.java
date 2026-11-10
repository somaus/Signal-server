package com.signal.domain.chat.repository;

import com.signal.domain.chat.entity.ChatSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findBySessionId(String sessionId);

    List<ChatSession> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByAnonymousId(String anonymousId);

    /** 저장에 동의한 비회원 상담만 로그인한 회원 것으로 옮긴다. 동의하지 않은 상담은 그대로 두고, anonymousId는 지우지 않는다. */
    @Modifying
    @Query("update ChatSession c set c.userId = :userId "
            + "where c.anonymousId = :anonymousId and c.userId is null and c.saveConsent = true")
    int claimConsentedByAnonymousId(@Param("anonymousId") String anonymousId, @Param("userId") Long userId);
}
