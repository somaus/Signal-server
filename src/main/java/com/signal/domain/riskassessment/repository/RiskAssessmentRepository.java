package com.signal.domain.riskassessment.repository;

import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    List<RiskAssessment> findByUserId(Long userId);

    /** 실패한 분석은 비회원 이용 횟수에 넣지 않는다(얼굴이 없는 사진을 올려도 횟수가 줄지 않게). */
    long countByAnonymousIdAndStatusNot(String anonymousId, RiskAssessmentStatus status);

    List<RiskAssessment> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, RiskAssessmentStatus status);
}
