package com.signal.domain.chat.dto.response;

import com.signal.domain.chat.entity.ChatMessage;
import java.util.List;

public record ChatMessagesResponse(List<ChatMessageResponse> messages, boolean sessionEnded) {

    public static ChatMessagesResponse from(List<ChatMessage> messages, boolean sessionEnded) {
        return new ChatMessagesResponse(messages.stream().map(ChatMessageResponse::from).toList(), sessionEnded);
    }
}
