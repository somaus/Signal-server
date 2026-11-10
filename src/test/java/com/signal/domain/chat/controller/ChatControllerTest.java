package com.signal.domain.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.signal.domain.chat.engine.ChatEngineResponse;
import com.signal.domain.chat.engine.SituationType;
import com.signal.domain.chat.entity.ChatMessage;
import com.signal.domain.chat.entity.ChatSession;
import com.signal.domain.chat.progress.ConsultationProgress;
import com.signal.domain.chat.service.ChatService;
import com.signal.domain.chat.service.SendMessageResult;
import com.signal.domain.user.repository.UserRepository;
import com.signal.global.config.SecurityConfig;
import com.signal.global.exception.ErrorCode;
import com.signal.global.exception.GlobalExceptionHandler;
import com.signal.global.exception.SignalException;
import com.signal.global.security.JwtAccessDeniedHandler;
import com.signal.global.security.JwtAuthenticationEntryPoint;
import com.signal.global.security.jwt.JwtAuthenticationFilter;
import com.signal.global.security.jwt.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 채팅은 비회원도 X-Anonymous-Id로 쓸 수 있어 경로는 열려 있고, 소유권 검사는 서비스가 한다. */
@WebMvcTest(ChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:3000")
class ChatControllerTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.signal.global.file.FileUrlSigner fileUrlSigner;

    private static final RequestPostProcessor USER_1 = authentication(new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private ChatSession session(boolean saveConsent) {
        return ChatSession.builder().sessionId("s-1").userId(1L).saveConsent(saveConsent).build();
    }

    @Test
    void 로그인_사용자가_세션을_만들면_201과_저장_동의를_반환한다() throws Exception {
        when(chatService.createSession(1L, null, true)).thenReturn(session(true));

        mockMvc.perform(post("/api/v1/chat/sessions").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"saveConsent\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.saveConsent").value(true));
    }

    @Test
    void 비회원은_본문_없이_익명_ID로_세션을_만들고_저장_동의는_false다() throws Exception {
        when(chatService.createSession(null, "anon-1", false)).thenReturn(session(false));

        mockMvc.perform(post("/api/v1/chat/sessions").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saveConsent").value(false));
    }

    @Test
    void 익명_ID도_없으면_서비스_에러를_400으로_매핑한다() throws Exception {
        when(chatService.createSession(isNull(), isNull(), eq(false)))
                .thenThrow(new SignalException(ErrorCode.ANONYMOUS_ID_REQUIRED));

        mockMvc.perform(post("/api/v1/chat/sessions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANONYMOUS_ID_REQUIRED"));
    }

    @Test
    void 내_세션_목록을_반환한다() throws Exception {
        when(chatService.getMySessions(1L)).thenReturn(List.of(session(true)));

        mockMvc.perform(get("/api/v1/chat/sessions").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s-1"));
    }

    @Test
    void 비로그인으로_세션_목록을_요청하면_401이다() throws Exception {
        when(chatService.getMySessions(null)).thenThrow(new SignalException(ErrorCode.UNAUTHORIZED));

        mockMvc.perform(get("/api/v1/chat/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 남의_세션이면_404다() throws Exception {
        when(chatService.getSession("s-x", 1L, null)).thenThrow(new SignalException(ErrorCode.NOT_FOUND));

        mockMvc.perform(get("/api/v1/chat/sessions/s-x").with(USER_1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 세션을_삭제하면_204다() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/sessions/s-1").header("X-Anonymous-Id", "anon-1"))
                .andExpect(status().isNoContent());
        verify(chatService).deleteSession("s-1", null, "anon-1");
    }

    @Test
    void 메시지를_보내면_답변과_진행도를_반환한다() throws Exception {
        ChatMessage bot = ChatMessage.builder().chatSessionId(1L).content("답변").build();
        ReflectionTestUtils.setField(bot, "id", 42L);
        ChatEngineResponse engine = new ChatEngineResponse(
                "답변", SituationType.IMAGE_ABUSE, List.of("증거 보존"), false, List.of());
        when(chatService.sendMessage("s-1", 1L, null, "도와주세요"))
                .thenReturn(new SendMessageResult(bot, engine, false, ConsultationProgress.notStarted()));

        mockMvc.perform(post("/api/v1/chat/sessions/s-1/messages").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"도와주세요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(42))
                .andExpect(jsonPath("$.reply").value("답변"))
                .andExpect(jsonPath("$.situationType").value("IMAGE_ABUSE"))
                .andExpect(jsonPath("$.sessionEnded").value(false))
                .andExpect(jsonPath("$.progress.progressPercent").value(0));
    }

    @Test
    void 빈_메시지나_2000자_초과_메시지는_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/chat/sessions/s-1/messages").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(post("/api/v1/chat/sessions/s-1/messages").with(USER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "가".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chatService);
    }

    @Test
    void 비회원_이용_횟수를_넘으면_429다() throws Exception {
        when(chatService.sendMessage(anyString(), any(), any(), anyString()))
                .thenThrow(new SignalException(ErrorCode.ANONYMOUS_CHAT_LIMIT_EXCEEDED));

        mockMvc.perform(post("/api/v1/chat/sessions/s-1/messages").header("X-Anonymous-Id", "anon-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ANONYMOUS_CHAT_LIMIT_EXCEEDED"));
    }

    @Test
    void 메시지_목록을_반환한다() throws Exception {
        when(chatService.getMessages("s-1", 1L, null))
                .thenReturn(List.of(ChatMessage.builder().chatSessionId(1L).content("안녕").build()));

        mockMvc.perform(get("/api/v1/chat/sessions/s-1/messages").with(USER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].content").value("안녕"));
    }
}
