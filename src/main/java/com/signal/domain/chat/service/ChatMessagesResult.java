package com.signal.domain.chat.service;

import com.signal.domain.chat.entity.ChatMessage;
import java.util.List;

/** 메시지 목록과 그 세션의 종료 여부. 클라이언트가 이전 상담을 이어서 열 때 입력창을 닫을지 정하는 데 쓴다. */
public record ChatMessagesResult(List<ChatMessage> messages, boolean sessionEnded) {
}
