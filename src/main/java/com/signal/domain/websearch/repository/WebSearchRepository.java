package com.signal.domain.websearch.repository;

import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.entity.WebSearchStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WebSearchRepository extends JpaRepository<WebSearch, Long> {

    Optional<WebSearch> findByDetectionId(Long detectionId);

    long countByOwnerKeyAndCreatedAtGreaterThanEqualAndStatusNot(String ownerKey, LocalDateTime since, WebSearchStatus status);

    long countByCreatedAtGreaterThanEqualAndStatusNot(LocalDateTime since, WebSearchStatus status);

    @Transactional
    void deleteByOwnerKey(String ownerKey);

    /** 소유자 키를 바꾼다. 비회원 탐지를 회원으로 옮길 때 웹 검색 기록도 같이 옮겨, 탈퇴 때 지워지고 하루 한도가 합쳐지게 한다. */
    @Modifying
    @Query("update WebSearch w set w.ownerKey = :to where w.ownerKey = :from")
    int updateOwnerKey(@Param("from") String from, @Param("to") String to);

    @Transactional
    int deleteByCreatedAtBefore(LocalDateTime before);
}
