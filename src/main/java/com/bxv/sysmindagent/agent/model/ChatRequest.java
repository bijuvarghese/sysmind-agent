package com.bxv.sysmindagent.agent.model;

import java.util.List;

public record ChatRequest(
        String message,
        List<ChatMessage> messages
) {

    public ChatRequest {
        if ((messages == null || messages.isEmpty()) && message != null && !message.isBlank()) {
            messages = List.of(ChatMessage.user(message));
        } else {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public ChatRequest(String message) {
        this(message, List.of(ChatMessage.user(message)));
    }
}
