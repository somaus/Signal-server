package com.signal.domain.chat.dto.response;

import com.signal.domain.chat.engine.SituationType;
import com.signal.domain.chat.entity.ChatSession;
import java.time.LocalDateTime;

public record ChatSessionResponse(
        String sessionId,
        boolean saveConsent,
        String firstMessagePreview,
        SituationType lastSituationType,
        boolean lastCrisisDetected,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean sessionEnded
) {

    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(
                session.getSessionId(),
                session.isSaveConsent(),
                session.getFirstMessagePreview(),
                session.getLastSituationType(),
                session.isLastCrisisDetected(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                session.isSessionEnded());
    }
}
