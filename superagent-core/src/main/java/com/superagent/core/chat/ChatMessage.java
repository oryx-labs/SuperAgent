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
        Instant createdAt) {
}
