package com.signal.domain.riskassessment.repository;

import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    List<RiskAssessment> findByUserId(Long userId);

    /** 실패한 분석은 비회원 이용 횟수에 넣지 않는다(얼굴이 없는 사진을 올려도 횟수가 줄지 않게). */
    long countByAnonymousIdAndStatusNot(String anonymousId, RiskAssessmentStatus status);

    /** 서버가 재시작돼 결과가 나오지 못한 채 남은 접수 건을 세기 위한 것. */
    long countByAnonymousIdAndStatusAndCreatedAtBefore(
            String anonymousId, RiskAssessmentStatus status, LocalDateTime createdBefore);

    List<RiskAssessment> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, RiskAssessmentStatus status);
}
