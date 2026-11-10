package com.signal.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.signal.domain.chat.dto.response.ChatSummaryResponse;
import com.signal.domain.chat.dto.response.ConsultationProgressResponse;
import com.signal.domain.chat.engine.ChatEngine;
import com.signal.domain.chat.engine.ChatEngineResponse;
import com.signal.domain.chat.engine.ChatSpeaker;
import com.signal.domain.chat.engine.ChatTurn;
import com.signal.domain.chat.engine.SituationType;
import com.signal.domain.chat.entity.ChatMessage;
import com.signal.domain.chat.entity.ChatRole;
import com.signal.domain.chat.entity.ChatSession;
import com.signal.domain.chat.progress.ConsultationProgressStatus;
import com.signal.domain.chat.progress.ConsultationProgressTracker;
import com.signal.domain.chat.progress.ConsultationStage;
import com.signal.domain.chat.progress.StageAnalyzer;
import com.signal.domain.chat.progress.StageSnapshot;
import com.signal.domain.chat.progress.StageStatus;
import com.signal.domain.chat.repository.ChatMessageRepository;
import com.signal.domain.chat.repository.ChatSessionRepository;
import com.signal.domain.report.repository.ReportRepository;
import com.signal.domain.riskassessment.entity.RiskLevel;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.SignalException;
import com.signal.global.i18n.LanguageContext;
import com.signal.global.i18n.ResponseLanguage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ChatEngine chatEngine;

    @Mock
    private StageAnalyzer stageAnalyzer;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(chatSessionRepository, chatMessageRepository, reportRepository, chatEngine,
                new ConsultationProgressTracker(stageAnalyzer));
    }

    @Test
    void 비로그인으로_내_세션_목록을_조회하면_UNAUTHORIZED이고_저장소를_조회하지_않는다() {
        assertThatThrownBy(() -> chatService.getMySessions(null))
                .isInstanceOfSatisfying(SignalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(chatSessionRepository);
    }

    @Test
    void 세션을_생성하면_UUID_sessionId가_발급된다() {
        when(chatSessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChatSession session = chatService.createSession(1L, null, false);

        assertThat(session.getSessionId()).isNotBlank();
        assertThat(session.getCreatedAt()).isNotNull();
    }

    @Test
    void 메시지를_보내면_사용자_메시지와_봇_응답이_저장되고_엔진_결과가_반환된다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        ChatEngineResponse engineResponse = new ChatEngineResponse(
                "안녕하세요", SituationType.GENERAL, List.of("조금 더 알려주세요"), false, List.of());
        when(chatEngine.respond(eq("안녕"), any())).thenReturn(engineResponse);
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "안녕");

        assertThat(result.botMessage().getRole()).isEqualTo(ChatRole.BOT);
        assertThat(result.botMessage().getContent()).isEqualTo("안녕하세요");
        assertThat(result.engineResponse()).isEqualTo(engineResponse);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).save(captor.capture());
        List<ChatMessage> saved = captor.getAllValues();
        assertThat(saved.get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(saved.get(0).getContent()).isEqualTo("안녕");
        assertThat(saved.get(1).getRole()).isEqualTo(ChatRole.BOT);
    }

    @Test
    void 이전_메시지가_있으면_history로_변환해_엔진에_전달한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));

        ChatMessage prevUser = ChatMessage.builder()
                .chatSessionId(session.getId()).role(ChatRole.USER).content("전에 한 말").build();
        ChatMessage prevBot = ChatMessage.builder()
                .chatSessionId(session.getId()).role(ChatRole.BOT).content("전에 답한 말").build();
        when(chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(prevUser, prevBot));

        ChatEngineResponse engineResponse = new ChatEngineResponse(
                "답변", SituationType.GENERAL, List.of(), false, List.of());
        when(chatEngine.respond(eq("이어서 말할게요"), any())).thenReturn(engineResponse);
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        chatService.sendMessage("session-1", 1L, null, "이어서 말할게요");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatEngine).respond(eq("이어서 말할게요"), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).containsExactly(
                new ChatTurn(ChatSpeaker.USER, "전에 한 말"),
                new ChatTurn(ChatSpeaker.BOT, "전에 답한 말"));
    }

    @Test
    void 존재하지_않는_세션에_메시지를_보내면_예외가_발생한다() {
        when(chatSessionRepository.findBySessionId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage("unknown", 1L, null, "안녕"))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 존재하는_세션의_메시지목록을_시간순으로_반환한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        ChatMessage message = ChatMessage.builder()
                .chatSessionId(session.getId()).role(ChatRole.USER).content("안녕").build();
        when(chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(message));

        ChatMessagesResult result = chatService.getMessages("session-1", 1L, null);

        assertThat(result.messages()).containsExactly(message);
        assertThat(result.sessionEnded()).isFalse();
    }

    @Test
    void 종료된_세션의_메시지목록과_요약은_sessionEnded가_true다() {
        ChatSession session = endedSession();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        assertThat(chatService.getMessages("session-1", 1L, null).sessionEnded()).isTrue();
        assertThat(chatService.getSummary("session-1", 1L, null).sessionEnded()).isTrue();
    }

    @Test
    void 종료된_세션에_메시지를_보내면_답변을_만들지_않고_CHAT_SESSION_ENDED로_거절한다() {
        ChatSession session = endedSession();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> chatService.sendMessage("session-1", 1L, null, "또 말할게요"))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_SESSION_ENDED);

        verifyNoInteractions(chatEngine);
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void 종료된_세션이라도_남의_세션이면_종료_여부를_알리지_않고_FORBIDDEN이다() {
        ChatSession session = endedSession();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> chatService.sendMessage("session-1", 2L, null, "hi"))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    private ChatSession endedSession() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.markAwaitingEndConfirmation();
        session.resolveEndConfirmation(true);
        return session;
    }

    @Test
    void 존재하지_않는_세션의_메시지를_조회하면_예외가_발생한다() {
        when(chatSessionRepository.findBySessionId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getMessages("unknown", 1L, null))
                .isInstanceOf(SignalException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND);
    }

    @Test
    void 메시지가_없는_세션의_요약은_기본값을_반환한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        ChatSummaryResponse summary = chatService.getSummary("session-1", 1L, null);

        assertThat(summary.situation()).isEqualTo("상담 시작 전");
        assertThat(summary.riskLevel()).isEqualTo(RiskLevel.LOW);
        // 새 상담은 NOT_STARTED, 진행률 0
        assertThat(summary.progressPercent()).isZero();
        assertThat(summary.progress().status()).isEqualTo(ConsultationProgressStatus.NOT_STARTED);
        assertThat(summary.progress().currentStage()).isNull();
        assertThat(summary.recommendedSteps()).containsExactly("증거 보존", "플랫폼 신고", "전문 기관 상담");
        assertThat(summary.recommendedStepCodes()).hasSize(3);
    }

    @Test
    void 요약은_요청_언어로_상황과_남은_단계를_반환한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        LanguageContext.set(ResponseLanguage.EN);
        try {
            ChatSummaryResponse summary = chatService.getSummary("session-1", 1L, null);

            assertThat(summary.situation()).isEqualTo("Before the session");
            assertThat(summary.riskDescription()).isEqualTo("No particular warning signs.");
            assertThat(summary.recommendedSteps()).containsExactly(
                    "Preserve evidence", "Report to the platform", "Contact a support organization");
            // 표시 문구는 언어마다 달라도 코드는 같다.
            assertThat(summary.recommendedStepCodes()).containsExactly(
                    ConsultationStage.EVIDENCE_PRESERVATION,
                    ConsultationStage.PLATFORM_REPORT,
                    ConsultationStage.AGENCY_GUIDANCE);
        } finally {
            LanguageContext.clear();
        }
    }

    @Test
    void 남은_단계는_서버가_기록한_단계_상태를_기준으로_계산한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.recordEngineResult(SituationType.IMAGE_ABUSE, false, false);
        session.updateStageSnapshot(new StageSnapshot(SituationType.IMAGE_ABUSE, Map.of(
                ConsultationStage.EVIDENCE_PRESERVATION, StageStatus.COMPLETED,
                ConsultationStage.PLATFORM_REPORT, StageStatus.COMPLETED,
                ConsultationStage.AGENCY_GUIDANCE, StageStatus.NOT_STARTED)));
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        ChatSummaryResponse summary = chatService.getSummary("session-1", 1L, null);
        assertThat(summary.recommendedSteps()).containsExactly("전문 기관 상담");
        assertThat(summary.recommendedStepCodes()).containsExactly(ConsultationStage.AGENCY_GUIDANCE);
    }

    @Test
    void 일반_상담의_남은_단계에는_증거_보존과_신고가_나오지_않는다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.recordEngineResult(SituationType.GENERAL, false, false);
        session.updateStageSnapshot(new StageSnapshot(SituationType.GENERAL, Map.of(
                ConsultationStage.AGENCY_GUIDANCE, StageStatus.NOT_STARTED)));
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        assertThat(chatService.getSummary("session-1", 1L, null).recommendedSteps())
                .containsExactly("전문 기관 상담");
    }

    @Test
    void 마지막_안내_단계까지_전달되면_안내_뒤에_종료를_제안하고_진행률은_100퍼센트다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.recordEngineResult(SituationType.IMAGE_ABUSE, false, false);
        session.updateStageSnapshot(new StageSnapshot(SituationType.IMAGE_ABUSE, Map.of(
                ConsultationStage.SITUATION_CHECK, StageStatus.COMPLETED,
                ConsultationStage.EMOTIONAL_SUPPORT, StageStatus.COMPLETED,
                ConsultationStage.EVIDENCE_PRESERVATION, StageStatus.COMPLETED,
                ConsultationStage.PLATFORM_REPORT, StageStatus.COMPLETED,
                ConsultationStage.AGENCY_GUIDANCE, StageStatus.NOT_STARTED)));
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        stubPreviousExchange(session);
        when(chatEngine.respond(eq("고마워요"), any())).thenReturn(new ChatEngineResponse(
                "평범한 답변", SituationType.IMAGE_ABUSE, List.of(), false, List.of()));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "고마워요");

        String reply = result.engineResponse().reply();
        assertThat(reply).startsWith("평범한 답변");
        assertThat(reply).contains("d4u.stop.or.kr");              // 마지막 안내 블록이 사라지지 않는다
        assertThat(reply).contains("마무리해도 괜찮을까요");          // 그 뒤에 종료 제안이 덧붙는다
        assertThat(result.engineResponse().recommendedAgencies()).isNotEmpty();
        assertThat(session.isAwaitingEndConfirmation()).isTrue();
        assertThat(session.isAgenciesRecommended()).isTrue();
        assertThat(result.sessionEnded()).isFalse();
        assertThat(result.progress().status()).isEqualTo(ConsultationProgressStatus.COMPLETED);

        ChatSummaryResponse summary = chatService.getSummary("session-1", 1L, null);
        assertThat(summary.progressPercent()).isEqualTo(100);
        assertThat(summary.progress().completedCount()).isEqualTo(5);
        assertThat(summary.progress().totalCount()).isEqualTo(5);
    }

    @Test
    void 이미지_피해의_첫_메시지에는_증거_보존_안내만_붙는다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatEngine.respond(eq("제 사진이 유포됐어요"), any())).thenReturn(new ChatEngineResponse(
                "신고 방법을 알려드릴게요", SituationType.IMAGE_ABUSE, List.of(), false, List.of()));
        when(stageAnalyzer.analyze(any(), any(), any())).thenReturn(Map.of(
                ConsultationStage.EVIDENCE_PRESERVATION, StageStatus.IN_PROGRESS,
                ConsultationStage.PLATFORM_REPORT, StageStatus.IN_PROGRESS));

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "제 사진이 유포됐어요");

        assertThat(result.engineResponse().reply()).startsWith("신고 방법을 알려드릴게요").contains("증거 보존");
        assertThat(result.engineResponse().reply()).doesNotContain("d4u.stop.or.kr");
        assertThat(result.progress().status()).isEqualTo(ConsultationProgressStatus.IN_PROGRESS);
        assertThat(result.progress().completedStages()).containsExactly(ConsultationStage.EVIDENCE_PRESERVATION);
        assertThat(result.progress().currentStage()).isEqualTo(ConsultationStage.SITUATION_CHECK);
        assertThat(result.progress().progressPercent()).isEqualTo(20);
    }

    @Test
    void 첫_메시지에_증거_URL이_있으면_안내_블록을_붙이지_않는다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChatEngineResponse engineResponse = new ChatEngineResponse(
                "주소를 남겨주셔서 감사해요", SituationType.IMAGE_ABUSE, List.of(), false, List.of());
        when(chatEngine.respond(eq("유포됐어요 https://example.com/post/1"), any())).thenReturn(engineResponse);

        SendMessageResult result = chatService.sendMessage(
                "session-1", 1L, null, "유포됐어요 https://example.com/post/1");

        assertThat(result.engineResponse()).isEqualTo(engineResponse);
        assertThat(result.progress().completedStages()).containsExactly(ConsultationStage.EVIDENCE_PRESERVATION);
    }

    @Test
    void 두번째_메시지에는_증거_보존_안내가_붙고_해당_단계와_상황_파악이_완료된다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubPreviousExchange(session);
        when(chatEngine.respond(eq("인스타그램이에요"), any())).thenReturn(new ChatEngineResponse(
                "인스타그램이군요", SituationType.IMAGE_ABUSE, List.of(), false, List.of()));

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "인스타그램이에요");

        assertThat(result.engineResponse().reply()).startsWith("인스타그램이군요").contains("증거 보존");
        assertThat(result.progress().stages().get(ConsultationStage.EVIDENCE_PRESERVATION))
                .isEqualTo(StageStatus.COMPLETED);
        assertThat(result.progress().completedStages()).containsExactly(
                ConsultationStage.SITUATION_CHECK, ConsultationStage.EVIDENCE_PRESERVATION);
        assertThat(result.progress().progressPercent()).isEqualTo(40);
    }

    @Test
    void 일반_상담에서_기관_안내를_마친_뒤_이미지_피해로_바뀌면_이미지_피해_기관을_다시_안내한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.recordEngineResult(SituationType.GENERAL, false, true);
        session.updateStageSnapshot(new StageSnapshot(SituationType.GENERAL, Map.of(
                ConsultationStage.SITUATION_CHECK, StageStatus.COMPLETED,
                ConsultationStage.EMOTIONAL_SUPPORT, StageStatus.COMPLETED,
                ConsultationStage.AGENCY_GUIDANCE, StageStatus.COMPLETED)));
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubPreviousExchange(session);
        when(chatEngine.respond(eq("사실 제 사진이 합성돼서 유포됐어요"), any())).thenReturn(new ChatEngineResponse(
                "많이 놀라셨겠어요", SituationType.IMAGE_ABUSE, List.of(), false, List.of()));

        SendMessageResult first = chatService.sendMessage("session-1", 1L, null, "사실 제 사진이 합성돼서 유포됐어요");

        assertThat(first.progress().stages().get(ConsultationStage.AGENCY_GUIDANCE))
                .isEqualTo(StageStatus.NOT_STARTED);
        assertThat(first.engineResponse().reply()).contains("증거 보존");   // 이미지 피해라서 증거 보존부터 안내

        when(chatEngine.respond(eq("인스타그램이에요"), any())).thenReturn(new ChatEngineResponse(
                "인스타그램이군요", SituationType.IMAGE_ABUSE, List.of(), false, List.of()));
        SendMessageResult second = chatService.sendMessage("session-1", 1L, null, "인스타그램이에요");

        assertThat(second.engineResponse().reply()).contains("d4u.stop.or.kr");
        assertThat(second.engineResponse().recommendedAgencies()).anyMatch(agency -> agency.contains("1377"));
    }

    @Test
    void 단계_판정은_LLM에_넘기는_최근_대화가_아니라_세션_전체의_사용자_메시지_수를_쓴다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // 오래된 대화라 최근 10개만 넘어가도(history가 비어 있어도) 저장된 사용자 메시지는 이미 많다.
        when(chatMessageRepository.countByChatSessionIdAndRole(session.getId(), ChatRole.USER)).thenReturn(30L);
        when(chatEngine.respond(eq("계속 얘기할게요"), any())).thenReturn(new ChatEngineResponse(
                "네, 말씀하세요", SituationType.GENERAL, List.of(), false, List.of()));

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "계속 얘기할게요");

        assertThat(result.progress().stages().get(ConsultationStage.SITUATION_CHECK))
                .isEqualTo(StageStatus.COMPLETED);
    }

    @Test
    void LLM이_모든_단계를_완료로_판단해도_서버는_완료로_인정하지_않는다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatEngine.respond(eq("도와주세요"), any())).thenReturn(new ChatEngineResponse(
                "네, 도와드릴게요", SituationType.IMAGE_ABUSE, List.of(), false, List.of()));
        Map<ConsultationStage, StageStatus> allCompleted = new java.util.EnumMap<>(ConsultationStage.class);
        for (ConsultationStage stage : ConsultationStage.values()) {
            allCompleted.put(stage, StageStatus.COMPLETED);
        }
        when(stageAnalyzer.analyze(any(), any(), any())).thenReturn(allCompleted);

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "도와주세요");

        assertThat(result.progress().status()).isEqualTo(ConsultationProgressStatus.IN_PROGRESS);
        // LLM이 완료라고 해도 서버가 안내를 내보낸 증거 보존만 완료다(첫 메시지의 이미지 피해 증거 안내).
        assertThat(result.progress().completedStages()).containsExactly(ConsultationStage.EVIDENCE_PRESERVATION);
        assertThat(result.progress().stages().get(ConsultationStage.AGENCY_GUIDANCE))
                .isEqualTo(StageStatus.IN_PROGRESS);
        assertThat(session.isAwaitingEndConfirmation()).isFalse();
    }

    @Test
    void 위기_메시지에는_안내_블록과_종료_제안을_붙이지_않는다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubPreviousExchange(session);
        ChatEngineResponse crisis = new ChatEngineResponse(
                "위기 응답", SituationType.IMAGE_ABUSE, List.of("상담 연결"), true, List.of("자살예방 상담전화 (109)"));
        when(chatEngine.respond(eq("죽고 싶어요"), any())).thenReturn(crisis);

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "죽고 싶어요");

        assertThat(result.engineResponse()).isEqualTo(crisis);
        assertThat(session.isAwaitingEndConfirmation()).isFalse();
        assertThat(result.progress().stages().get(ConsultationStage.EMOTIONAL_SUPPORT))
                .isEqualTo(StageStatus.IN_PROGRESS);
    }

    @Test
    void 일반_상담은_해당없는_단계를_제외하고_진행률을_계산한다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatEngine.respond(eq("안녕하세요"), any())).thenReturn(new ChatEngineResponse(
                "안녕하세요", SituationType.GENERAL, List.of(), false, List.of()));
        when(stageAnalyzer.analyze(any(), any(), any())).thenThrow(new IllegalStateException("LLM 실패"));

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "안녕하세요");

        assertThat(result.progress().stages().get(ConsultationStage.EVIDENCE_PRESERVATION))
                .isEqualTo(StageStatus.NOT_APPLICABLE);
        // 인사만 한 첫 메시지는 어떤 단계도 완료시키지 않는다(예전에는 정서 안정이 자동 완료돼 33%였다).
        assertThat(result.progress().progressPercent()).isZero();
        assertThat(ConsultationProgressResponse.from(result.progress()).totalCount()).isEqualTo(3);
    }

    private void stubPreviousExchange(ChatSession session) {
        ChatMessage prevUser = ChatMessage.builder()
                .chatSessionId(session.getId()).role(ChatRole.USER).content("전에 한 말").build();
        ChatMessage prevBot = ChatMessage.builder()
                .chatSessionId(session.getId()).role(ChatRole.BOT).content("전에 답한 말").build();
        when(chatMessageRepository.findByChatSessionIdOrderByCreatedAtAsc(session.getId()))
                .thenReturn(List.of(prevUser, prevBot));
        when(chatMessageRepository.countByChatSessionIdAndRole(session.getId(), ChatRole.USER)).thenReturn(1L);
    }

    @Test
    void 종료_제안에_긍정하면_세션이_종료된다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.recordEngineResult(SituationType.IMAGE_ABUSE, false, true);
        session.markEvidenceUrlMentioned();
        session.markAwaitingEndConfirmation();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "네 좋아요");

        assertThat(result.sessionEnded()).isTrue();
        assertThat(session.isAwaitingEndConfirmation()).isFalse();
    }

    @Test
    void 종료_제안에_더_얘기하고_싶다고_하면_대화가_계속된다() {
        ChatSession session = ChatSession.builder().sessionId("session-1").userId(1L).build();
        session.recordEngineResult(SituationType.IMAGE_ABUSE, false, true);
        session.markEvidenceUrlMentioned();
        session.markAwaitingEndConfirmation();
        when(chatSessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));
        when(reportRepository.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(chatMessageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChatEngineResponse engineResponse = new ChatEngineResponse(
                "더 말씀해주세요", SituationType.IMAGE_ABUSE, List.of(), false, List.of());
        when(chatEngine.respond(eq("더 얘기하고 싶어요"), any())).thenReturn(engineResponse);

        SendMessageResult result = chatService.sendMessage("session-1", 1L, null, "더 얘기하고 싶어요");

        assertThat(result.sessionEnded()).isFalse();
        assertThat(session.isAwaitingEndConfirmation()).isFalse();
        assertThat(result.engineResponse().reply()).isEqualTo("더 말씀해주세요");
    }
}
