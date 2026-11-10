package com.signal.domain.chat.service;

import com.signal.domain.chat.dto.response.ChatSummaryResponse;
import com.signal.domain.chat.dto.response.ConsultationProgressResponse;
import com.signal.domain.chat.engine.ChatEngine;
import com.signal.domain.chat.engine.ChatEngineResponse;
import com.signal.domain.chat.engine.ChatSpeaker;
import com.signal.domain.chat.engine.ChatTurn;
import com.signal.domain.chat.engine.SituationType;
import com.signal.domain.chat.guidance.AgencyCatalog;
import com.signal.domain.chat.guidance.GuidancePlanner;
import com.signal.domain.chat.guidance.GuidanceText;
import com.signal.domain.chat.guidance.SafetySignals;
import com.signal.domain.chat.entity.ChatMessage;
import com.signal.domain.chat.entity.ChatRole;
import com.signal.domain.chat.entity.ChatSession;
import com.signal.domain.chat.repository.ChatMessageRepository;
import com.signal.domain.chat.progress.ConsultationProgress;
import com.signal.domain.chat.progress.ConsultationProgressStatus;
import com.signal.domain.chat.progress.ConsultationProgressTracker;
import com.signal.domain.chat.progress.ConsultationStage;
import com.signal.domain.chat.progress.StageFacts;
import com.signal.domain.chat.progress.StageRules;
import com.signal.domain.chat.progress.StageSnapshot;
import com.signal.domain.chat.progress.StageStatus;
import com.signal.domain.chat.repository.ChatSessionRepository;
import com.signal.domain.report.entity.Report;
import com.signal.domain.report.entity.ReportStatus;
import com.signal.domain.report.repository.ReportRepository;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ChatService {

    private static final long ANONYMOUS_CHAT_SESSION_LIMIT = 5;
    private static final int MAX_HISTORY_MESSAGES = 10;   // LLM에 넘길 이전 대화 최대 개수 (토큰 사용량 상한)
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ReportRepository reportRepository;
    private final ChatEngine chatEngine;
    private final ConsultationProgressTracker progressTracker;

    @Transactional
    public ChatSession createSession(Long userId, String anonymousId, boolean saveConsent) {
        if (userId == null) {
            validateAnonymousUsage(anonymousId);
        }

        ChatSession session = ChatSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId(userId)
                .anonymousId(userId == null ? anonymousId : null)
                .saveConsent(saveConsent)
                .build();
        return chatSessionRepository.save(session);
    }

    @Transactional
    public SendMessageResult sendMessage(String sessionId, Long userId, String anonymousId, String content) {
        ChatSession session = getOwnedSession(sessionId, userId, anonymousId);
        // 종료된 상담에는 답변을 만들지 않는다. 단 위기 신호는 버리지 않고 평소처럼 위기 응답과 기관 안내를 내보낸다.
        if (session.isSessionEnded() && !SafetySignals.isCrisis(content)) {
            throw new SignalException(ErrorCode.CHAT_SESSION_ENDED);
        }
        List<ChatTurn> history = buildHistory(session.getId());
        // 단계 판정에는 LLM에 넘기는 최근 10개가 아니라 세션 전체의 사용자 메시지 수를 쓴다(이번 메시지 포함).
        int userTurns = (int) chatMessageRepository.countByChatSessionIdAndRole(session.getId(), ChatRole.USER) + 1;

        session.recordUserMessage(content);
        if (URL_PATTERN.matcher(content).find()) {
            session.markEvidenceUrlMentioned();
        }

        chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(session.getId())
                .role(ChatRole.USER)
                .content(content)
                .build());

        ChatEngineResponse engineResponse = resolveEngineResponse(session, userId, content, history, userTurns);

        ChatMessage botMessage = chatMessageRepository.save(ChatMessage.builder()
                .chatSessionId(session.getId())
                .role(ChatRole.BOT)
                .content(engineResponse.reply())
                .build());

        return new SendMessageResult(
                botMessage, engineResponse, session.isSessionEnded(),
                ConsultationProgress.from(session.getStageSnapshot()));
    }

    private List<ChatTurn> buildHistory(Long chatSessionId) {
        List<ChatMessage> messages = chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(chatSessionId);
        int fromIndex = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        return messages.subList(fromIndex, messages.size()).stream()
                .map(message -> new ChatTurn(
                        message.getRole() == ChatRole.USER ? ChatSpeaker.USER : ChatSpeaker.BOT,
                        message.getContent()))
                .toList();
    }

    private ChatEngineResponse resolveEngineResponse(
            ChatSession session, Long userId, String content, List<ChatTurn> history, int userTurns) {
        ResponseLanguage language = LanguageContext.current();
        if (session.isAwaitingEndConfirmation()) {
            boolean confirmed = GuidanceText.isEndConfirmation(content);
            session.resolveEndConfirmation(confirmed);
            if (confirmed) {
                return new ChatEngineResponse(
                        GuidanceText.endClosing(language), session.getLastSituationType(), List.of(), false, List.of());
            }
        }

        ChatEngineResponse engineResponse = chatEngine.respond(content, history);
        boolean agenciesGiven = !engineResponse.recommendedAgencies().isEmpty();
        session.recordEngineResult(engineResponse.situationType(), engineResponse.crisisDetected(), agenciesGiven);

        ConsultationProgressStatus statusBefore = ConsultationProgress.from(session.getStageSnapshot()).status();

        List<Report> reports = myReports(userId);
        List<ChatTurn> turns = new ArrayList<>(history);
        turns.add(new ChatTurn(ChatSpeaker.USER, content));
        turns.add(new ChatTurn(ChatSpeaker.BOT, engineResponse.reply()));
        StageSnapshot snapshot = progressTracker.update(
                session.getStageSnapshot(), engineResponse.situationType(), turns,
                new StageFacts(
                        engineResponse.crisisDetected(),
                        session.isEverCrisisDetected(),
                        session.isEvidenceUrlMentioned(),
                        !reports.isEmpty(),
                        hasFinalizedReport(reports),
                        userTurns));

        // 서버가 이번 턴에 붙일 안내 블록을 고른다(한 턴에 하나). 블록을 내보낸 단계만 서버가 완료로 기록한다.
        Optional<ConsultationStage> delivered = GuidancePlanner.next(
                snapshot.type(), snapshot.stages(), userTurns, engineResponse.crisisDetected());
        String reply = engineResponse.reply();
        List<String> agencies = engineResponse.recommendedAgencies();
        boolean changed = false;
        if (delivered.isPresent()) {
            changed = true;
            ConsultationStage stage = delivered.get();
            snapshot = StageRules.markDelivered(snapshot, stage);
            reply = reply + "\n\n" + GuidanceText.guidanceBlock(stage, snapshot.type(), language);
            if (stage == ConsultationStage.AGENCY_GUIDANCE) {
                session.markAgenciesRecommended();
                agencies = snapshot.type() == SituationType.IMAGE_ABUSE
                        ? AgencyCatalog.imageAbuse(language)
                        : AgencyCatalog.generalSupport(language);
            }
        }
        session.updateStageSnapshot(snapshot);

        // 종료 제안은 "방금 모든 단계가 끝난" 턴에 한 번만, 안내 블록 뒤에 덧붙인다(위기 메시지에는 하지 않는다).
        // 사용자가 계속하겠다고 답한 뒤에는 이미 완료 상태라 다시 묻지 않는다.
        boolean justCompleted = statusBefore != ConsultationProgressStatus.COMPLETED
                && ConsultationProgress.from(snapshot).status() == ConsultationProgressStatus.COMPLETED;
        if (justCompleted && !engineResponse.crisisDetected()
                && !session.isSessionEnded() && !session.isAwaitingEndConfirmation()) {
            session.markAwaitingEndConfirmation();
            reply = reply + "\n\n" + GuidanceText.endPrompt(language);
            changed = true;
        }

        if (!changed) {
            return engineResponse;
        }
        return new ChatEngineResponse(
                reply, engineResponse.situationType(), engineResponse.suggestedActions(),
                engineResponse.crisisDetected(), agencies);
    }

    public ChatMessagesResult getMessages(String sessionId, Long userId, String anonymousId) {
        ChatSession session = getOwnedSession(sessionId, userId, anonymousId);
        return new ChatMessagesResult(
                chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(session.getId()),
                session.isSessionEnded());
    }

    public List<ChatSession> getMySessions(Long userId) {
        // userId가 null이면 파생 쿼리가 user_id IS NULL로 바뀌어 모든 익명 세션이 조회된다.
        if (userId == null) {
            throw new SignalException(ErrorCode.UNAUTHORIZED);
        }
        return chatSessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public ChatSession getSession(String sessionId, Long userId, String anonymousId) {
        return getOwnedSession(sessionId, userId, anonymousId);
    }

    public ChatSummaryResponse getSummary(String sessionId, Long userId, String anonymousId) {
        ChatSession session = getOwnedSession(sessionId, userId, anonymousId);
        List<Report> reports = myReports(userId);
        boolean reportFinalized = hasFinalizedReport(reports);

        ConsultationProgress progress = ConsultationProgress.from(session.getStageSnapshot());

        List<ConsultationStage> remaining = remainingStages(session, reportFinalized);
        ResponseLanguage language = LanguageContext.current();
        return new ChatSummaryResponse(
                describeSituation(session),
                remaining.stream().map(stage -> GuidanceText.stepLabel(stage, language)).toList(),
                remaining,
                riskLevel(session),
                riskDescription(session),
                progress.progressPercent(),
                ConsultationProgressResponse.from(progress),
                session.isSessionEnded());
    }

    private List<Report> myReports(Long userId) {
        return userId == null ? List.of() : reportRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private boolean hasFinalizedReport(List<Report> reports) {
        return reports.stream().anyMatch(report -> report.getStatus() == ReportStatus.FINALIZED);
    }

    private String describeSituation(ChatSession session) {
        return GuidanceText.summarySituation(
                session.getLastSituationType(), session.isLastCrisisDetected(), LanguageContext.current());
    }

    /**
     * 아직 남은 안내 단계. 단계 상태(서버가 결정한 완료 여부)를 기준으로 하며, 상담 전이면 이미지 피해 기준의 전체 목록이다.
     * 상담 전에는 상황 유형을 알 수 없어 일부러 전체 단계를 보여 주고, 첫 메시지로 유형이 정해지면 유형에 맞게 줄어든다
     * (일반 상담이면 증거 보존·플랫폼 신고가 사라진다).
     * 마지막 메시지 이후에 신고 문서를 확정했을 수 있어서 신고 단계는 문서 상태도 함께 본다.
     */
    private List<ConsultationStage> remainingStages(ChatSession session, boolean reportFinalized) {
        StageSnapshot snapshot = session.getStageSnapshot();
        SituationType type = snapshot == null ? SituationType.IMAGE_ABUSE : snapshot.type();
        List<ConsultationStage> remaining = new ArrayList<>();
        for (ConsultationStage stage : List.of(
                ConsultationStage.EVIDENCE_PRESERVATION,
                ConsultationStage.PLATFORM_REPORT,
                ConsultationStage.AGENCY_GUIDANCE)) {
            if (!stage.isApplicableTo(type)) {
                continue;
            }
            StageStatus status = snapshot == null
                    ? StageStatus.NOT_STARTED
                    : snapshot.stages().getOrDefault(stage, StageStatus.NOT_STARTED);
            boolean done = status == StageStatus.COMPLETED
                    || (stage == ConsultationStage.PLATFORM_REPORT && reportFinalized)
                    || (stage == ConsultationStage.EVIDENCE_PRESERVATION && session.isEvidenceUrlMentioned());
            if (!done) {
                remaining.add(stage);
            }
        }
        return remaining;
    }

    private RiskLevel riskLevel(ChatSession session) {
        if (session.isLastCrisisDetected()) {
            return RiskLevel.HIGH;
        }
        if (session.getLastSituationType() == SituationType.IMAGE_ABUSE) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private String riskDescription(ChatSession session) {
        return GuidanceText.summaryRiskDescription(riskLevel(session), LanguageContext.current());
    }

    @Transactional
    public void deleteSession(String sessionId, Long userId, String anonymousId) {
        ChatSession session = getOwnedSession(sessionId, userId, anonymousId);
        chatMessageRepository.deleteByChatSessionId(session.getId());
        chatSessionRepository.delete(session);
    }

    private ChatSession getOwnedSession(String sessionId, Long userId, String anonymousId) {
        ChatSession session = chatSessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new SignalException(ErrorCode.NOT_FOUND));

        if (!session.isOwnedBy(userId, anonymousId)) {
            throw new SignalException(ErrorCode.FORBIDDEN);
        }

        return session;
    }

    private void validateAnonymousUsage(String anonymousId) {
        if (!StringUtils.hasText(anonymousId)) {
            throw new SignalException(ErrorCode.ANONYMOUS_ID_REQUIRED);
        }
        if (chatSessionRepository.countByAnonymousId(anonymousId) >= ANONYMOUS_CHAT_SESSION_LIMIT) {
            throw new SignalException(ErrorCode.ANONYMOUS_CHAT_LIMIT_EXCEEDED);
        }
    }
}
