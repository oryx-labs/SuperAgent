package com.superagent.core.chat;

import java.time.Instant;

/**
 * One normalised event in an agent's output stream, append-only and ordered
 * within a session by {@code seq}. {@code payload} is plain text for THINKING/TEXT
 * and a short label otherwise. Together these replay how a reply was produced.
 */
public record AgentEvent(
        String sessionId,
        String messageId,
        long seq,
        AgentEventType type,
        String payload,
        Instant createdAt) {

    public static AgentEvent of(String sessionId, long seq, AgentEventType type, String payload) {
        return new AgentEvent(sessionId, null, seq, type, payload, Instant.now());
    }
}
