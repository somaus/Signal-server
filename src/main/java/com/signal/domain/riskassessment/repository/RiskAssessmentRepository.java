package com.signal.domain.riskassessment.repository;

import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    List<RiskAssessment> findByUserId(Long userId);

    /** 실패한 분석은 비회원 이용 횟수에 넣지 않는다(얼굴이 없는 사진을 올려도 횟수가 줄지 않게). */
    long countByAnonymousIdAndStatusNot(String anonymousId, RiskAssessmentStatus status);

    /** 서버가 재시작돼 결과가 나오지 못한 채 남은 접수 건을 세기 위한 것. */
    long countByAnonymousIdAndStatusAndCreatedAtBefore(
            String anonymousId, RiskAssessmentStatus status, LocalDateTime createdBefore);

    List<RiskAssessment> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, RiskAssessmentStatus status);

    /**
     * 비회원으로 만든 분석 기록을 로그인한 회원 것으로 옮긴다. 분석이 끝나지 않은 건은 백그라운드 처리가
     * 엔티티를 통째로 다시 저장하므로 옮기지 않는다(옮긴 userId가 되돌려질 수 있다). 반환값은 옮긴 건수다.
     */
    @Modifying
    @Query("update RiskAssessment r set r.userId = :userId, r.anonymousId = null "
            + "where r.anonymousId = :anonymousId and r.userId is null and r.status <> :excluded")
    int claimByAnonymousId(@Param("anonymousId") String anonymousId, @Param("userId") Long userId,
            @Param("excluded") RiskAssessmentStatus excluded);
}
