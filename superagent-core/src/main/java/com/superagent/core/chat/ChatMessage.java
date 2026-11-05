package com.superagent.core.chat;

import java.time.Instant;

/**
 * A settled turn in a {@link ChatSession} — a user prompt or the agent's final
 * reply. Streaming increments live separately as {@link AgentEvent}s.
 */
public record ChatMessage(
        String id,
        String sessionId,
        MessageRole role,
        String content,
        Instant createdAt,
        /** Who wrote a USER message (username); null for the agent's replies and messages from before authors. */
        String author) {

    public ChatMessage(String id, String sessionId, MessageRole role, String content, Instant createdAt) {
        this(id, sessionId, role, content, createdAt, null);
    }
}
