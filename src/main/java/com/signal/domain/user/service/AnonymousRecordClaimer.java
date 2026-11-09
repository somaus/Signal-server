package com.signal.domain.user.service;

import com.signal.domain.chat.repository.ChatSessionRepository;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetectionStatus;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 비회원일 때 쌓은 기록을 로그인한 회원 것으로 옮긴다(#74). 클라이언트가 보내는 X-Anonymous-Id로 기록을
 * 찾으므로, 로그인할 때 같은 헤더가 함께 와야 한다.
 *
 * 옮기는 기준:
 * - 위험도 분석·딥페이크 탐지: 끝난 건(성공·실패)만. 처리 중인 건은 백그라운드 작업이 엔티티를 통째로 다시
 *   저장하기 때문에 옮긴 값이 되돌려질 수 있어 남겨 둔다.
 * - 상담: 저장에 동의한 세션만. 동의하지 않은 상담이 계정에 붙으면 동의 범위를 넘기 때문이다.
 *
 * 옮긴 기록은 anonymousId가 비워지므로 비회원 이용 횟수에서도 빠진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousRecordClaimer {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final DeepfakeDetectionRepository deepfakeDetectionRepository;
    private final ChatSessionRepository chatSessionRepository;

    /** 호출한 트랜잭션 안에서 실행된다. 식별자가 비어 있으면 아무것도 하지 않는다. */
    @Transactional
    public void claim(Long userId, String anonymousId) {
        if (userId == null || !StringUtils.hasText(anonymousId)) {
            return;
        }
        int assessments = riskAssessmentRepository.claimByAnonymousId(
                anonymousId, userId, RiskAssessmentStatus.PROCESSING);
        int detections = deepfakeDetectionRepository.claimByAnonymousId(
                anonymousId, userId, DeepfakeDetectionStatus.PROCESSING);
        int chats = chatSessionRepository.claimConsentedByAnonymousId(anonymousId, userId);
        if (assessments + detections + chats > 0) {
            log.info("비회원 기록을 회원으로 옮겼습니다: userId={}, 위험도={}, 탐지={}, 상담={}",
                    userId, assessments, detections, chats);
        }
    }
}
