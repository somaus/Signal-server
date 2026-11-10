package com.signal.domain.user.service;

import com.signal.domain.chat.repository.ChatSessionRepository;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.domain.websearch.repository.WebSearchRepository;
import com.signal.domain.websearch.service.WebSearchOwnerKey;
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
 * - 위험도 분석·딥페이크 탐지: 처리 중인 건도 옮긴다. 백그라운드 작업은 분석이 끝난 뒤에 기록을 읽어 바로
 *   저장하므로, 옮긴 값이 되돌려질 수 있는 구간은 그 읽기와 저장 사이의 아주 짧은 시간뿐이고, 되돌려져도
 *   그 기록이 비회원 것으로 남을 뿐이다. 제외하면 로그인 직후 만든 분석이 계정에 붙지 않는 쪽이 더 크다.
 * - 상담: 저장에 동의한 세션만. 동의하지 않은 상담이 계정에 붙으면 동의 범위를 넘기 때문이다.
 * - 탐지의 웹 검색 기록은 소유자 키가 따로 있어서 함께 옮긴다(탈퇴 때 지워지고 하루 한도가 합쳐진다).
 *
 * anonymousId는 지우지 않는다. 비우면 같은 기기에서 로그아웃한 뒤 비회원 한도가 다시 열리는 우회가 된다.
 * 소유 판정은 userId를 먼저 보므로 anonymousId가 남아 있어도 다른 사람이 볼 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousRecordClaimer {

    private final RiskAssessmentRepository riskAssessmentRepository;
    private final DeepfakeDetectionRepository deepfakeDetectionRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final WebSearchRepository webSearchRepository;

    /** 호출한 트랜잭션 안에서 실행된다. 식별자가 비어 있으면 아무것도 하지 않는다. */
    @Transactional
    public void claim(Long userId, String anonymousId) {
        if (userId == null || !StringUtils.hasText(anonymousId)) {
            return;
        }
        int assessments = riskAssessmentRepository.claimByAnonymousId(anonymousId, userId);
        int detections = deepfakeDetectionRepository.claimByAnonymousId(anonymousId, userId);
        int chats = chatSessionRepository.claimConsentedByAnonymousId(anonymousId, userId);
        webSearchRepository.updateOwnerKey(WebSearchOwnerKey.anonymous(anonymousId), WebSearchOwnerKey.user(userId));
        if (assessments + detections + chats > 0) {
            log.info("비회원 기록을 회원으로 옮겼습니다: userId={}, 위험도={}, 탐지={}, 상담={}",
                    userId, assessments, detections, chats);
        }
    }
}
