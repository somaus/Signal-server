package com.signal.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.signal.domain.chat.entity.ChatSession;
import com.signal.domain.chat.repository.ChatSessionRepository;
import com.signal.domain.deepfakedetection.entity.DeepfakeDetection;
import com.signal.domain.deepfakedetection.repository.DeepfakeDetectionRepository;
import com.signal.domain.riskassessment.entity.AssessmentFailureReason;
import com.signal.domain.riskassessment.entity.RiskAssessment;
import com.signal.domain.riskassessment.entity.RiskAssessmentStatus;
import com.signal.domain.riskassessment.repository.RiskAssessmentRepository;
import com.signal.domain.websearch.entity.WebSearch;
import com.signal.domain.websearch.repository.WebSearchRepository;
import com.signal.domain.websearch.service.WebSearchOwnerKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** 비회원 기록을 옮기는 update 쿼리가 실제 DB에서 유효하고, 옮기지 말아야 할 건을 건드리지 않는지 확인한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anonymous_claim_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@Import(AnonymousRecordClaimer.class)
class AnonymousRecordClaimerIntegrationTest {

    private static final Long USER_ID = 7L;
    private static final String ANON = "anon-1";

    @Autowired
    private AnonymousRecordClaimer claimer;

    @Autowired
    private RiskAssessmentRepository riskAssessmentRepository;

    @Autowired
    private DeepfakeDetectionRepository deepfakeDetectionRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private WebSearchRepository webSearchRepository;

    @Autowired
    private TestEntityManager entityManager;

    private RiskAssessment guestAssessment(String anonymousId, boolean failed) {
        RiskAssessment assessment = RiskAssessment.processing(null, anonymousId, "/uploads/a.png");
        if (failed) {
            assessment.fail(AssessmentFailureReason.NO_FACE_DETECTED);
        }
        return riskAssessmentRepository.saveAndFlush(assessment);
    }

    @Test
    void 끝난_위험도_분석은_회원으로_옮기고_비회원_식별자는_남긴다() {
        RiskAssessment done = guestAssessment(ANON, true);

        claimer.claim(USER_ID, ANON);
        entityManager.clear();

        RiskAssessment stored = riskAssessmentRepository.findById(done.getId()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(USER_ID);
        assertThat(stored.getAnonymousId()).isEqualTo(ANON);
    }

    @Test
    void 옮긴_뒤에도_비회원_이용_횟수에는_계속_잡힌다() {
        guestAssessment(ANON, false);

        claimer.claim(USER_ID, ANON);
        entityManager.clear();

        assertThat(riskAssessmentRepository.countByAnonymousIdAndStatusNot(ANON, RiskAssessmentStatus.FAILED))
                .isEqualTo(1);
    }

    @Test
    void 처리_중인_분석도_옮기고_다른_식별자의_기록은_옮기지_않는다() {
        RiskAssessment processing = guestAssessment(ANON, false);
        RiskAssessment others = guestAssessment("anon-2", true);

        claimer.claim(USER_ID, ANON);
        entityManager.clear();

        RiskAssessment moved = riskAssessmentRepository.findById(processing.getId()).orElseThrow();
        assertThat(moved.getStatus()).isEqualTo(RiskAssessmentStatus.PROCESSING);
        assertThat(moved.getUserId()).isEqualTo(USER_ID);
        assertThat(riskAssessmentRepository.findById(others.getId()).orElseThrow().getUserId()).isNull();
    }

    @Test
    void 이미_회원_것인_기록은_건드리지_않는다() {
        RiskAssessment mine = riskAssessmentRepository.saveAndFlush(
                RiskAssessment.processing(99L, null, "/uploads/b.png"));
        mine.fail(AssessmentFailureReason.TIMEOUT);
        riskAssessmentRepository.saveAndFlush(mine);

        int claimed = riskAssessmentRepository.claimByAnonymousId(ANON, USER_ID);

        assertThat(claimed).isZero();
    }

    @Test
    void 탐지_기록은_처리_중인_것도_옮긴다() {
        DeepfakeDetection processing = deepfakeDetectionRepository.saveAndFlush(
                DeepfakeDetection.builder().anonymousId(ANON).fileUrl("/uploads/c.png").build());
        DeepfakeDetection failed = DeepfakeDetection.builder().anonymousId(ANON).fileUrl("/uploads/d.png").build();
        failed.fail(com.signal.domain.deepfakedetection.entity.DetectionFailureReason.AI_SERVER_ERROR);
        failed = deepfakeDetectionRepository.saveAndFlush(failed);

        claimer.claim(USER_ID, ANON);
        entityManager.clear();

        assertThat(deepfakeDetectionRepository.findById(failed.getId()).orElseThrow().getUserId())
                .isEqualTo(USER_ID);
        assertThat(deepfakeDetectionRepository.findById(processing.getId()).orElseThrow().getUserId())
                .isEqualTo(USER_ID);
    }

    @Test
    void 옮긴_탐지의_웹_검색_기록은_회원_것이_되어_탈퇴_때_지워진다() {
        DeepfakeDetection detection = deepfakeDetectionRepository.saveAndFlush(
                DeepfakeDetection.builder().anonymousId(ANON).fileUrl("/uploads/e.png").build());
        webSearchRepository.saveAndFlush(WebSearch.processing(detection.getId(), WebSearchOwnerKey.anonymous(ANON)));
        webSearchRepository.saveAndFlush(
                WebSearch.processing(detection.getId() + 100, WebSearchOwnerKey.anonymous("anon-2")));

        claimer.claim(USER_ID, ANON);
        entityManager.clear();

        assertThat(webSearchRepository.findByDetectionId(detection.getId()).orElseThrow().getOwnerKey())
                .isEqualTo(WebSearchOwnerKey.user(USER_ID));
        assertThat(webSearchRepository.findByDetectionId(detection.getId() + 100).orElseThrow().getOwnerKey())
                .isEqualTo(WebSearchOwnerKey.anonymous("anon-2"));

        // 회원 탈퇴 때 WebSearchService.deleteByOwner가 하는 일
        webSearchRepository.deleteByOwnerKey(WebSearchOwnerKey.user(USER_ID));
        entityManager.flush();
        entityManager.clear();

        assertThat(webSearchRepository.findByDetectionId(detection.getId())).isEmpty();
        assertThat(webSearchRepository.findByDetectionId(detection.getId() + 100)).isPresent();
    }

    @Test
    void 상담은_저장에_동의한_세션만_옮긴다() {
        ChatSession consented = chatSessionRepository.saveAndFlush(
                ChatSession.builder().sessionId("s-1").anonymousId(ANON).saveConsent(true).build());
        ChatSession declined = chatSessionRepository.saveAndFlush(
                ChatSession.builder().sessionId("s-2").anonymousId(ANON).saveConsent(false).build());

        claimer.claim(USER_ID, ANON);
        entityManager.clear();

        ChatSession moved = chatSessionRepository.findById(consented.getId()).orElseThrow();
        assertThat(moved.getUserId()).isEqualTo(USER_ID);
        assertThat(moved.getAnonymousId()).isEqualTo(ANON);
        ChatSession kept = chatSessionRepository.findById(declined.getId()).orElseThrow();
        assertThat(kept.getUserId()).isNull();
        assertThat(kept.getAnonymousId()).isEqualTo(ANON);
    }

    @Test
    void 식별자가_비어_있으면_아무것도_옮기지_않는다() {
        RiskAssessment done = guestAssessment(ANON, true);

        claimer.claim(USER_ID, null);
        claimer.claim(USER_ID, "  ");
        entityManager.clear();

        assertThat(riskAssessmentRepository.findById(done.getId()).orElseThrow().getUserId()).isNull();
    }
}
