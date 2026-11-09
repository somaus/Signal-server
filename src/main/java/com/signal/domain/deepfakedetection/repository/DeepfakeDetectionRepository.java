package com.signal.domain.deepfakedetection.repository;

import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface DeepfakeDetectionRepository extends JpaRepository<DeepfakeDetection, Long> {

    List<DeepfakeDetection> findByUserId(Long userId);

    long countByAnonymousId(String anonymousId);

    /** 비회원으로 만든 탐지 기록을 로그인한 회원 것으로 옮긴다. 탐지 중인 건은 제외한다(RiskAssessmentRepository 참고). */
    @Modifying
    @Query("update DeepfakeDetection d set d.userId = :userId, d.anonymousId = null "
            + "where d.anonymousId = :anonymousId and d.userId is null and d.status <> :excluded")
    int claimByAnonymousId(@Param("anonymousId") String anonymousId, @Param("userId") Long userId,
            @Param("excluded") DeepfakeDetectionStatus excluded);
}
