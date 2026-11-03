package com.superagent.core.chat;

import java.time.Instant;

/**
 * A SuperAgent conversation, mapped one-to-one onto a CLI agent session:
 * {@code agentSessionId} holds the agent's own session id (e.g. the Claude Code
 * session UUID) so follow-up turns resume the same conversation, and {@code id}
 * doubles as that session id on the first turn.
 */
public record ChatSession(
        String id,
        String title,
        AgentType agentType,
        String agentSessionId,
        String workspaceDir,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** Returns a copy with the agent session id and a refreshed {@code updatedAt}. */
    public ChatSession withAgentSessionId(String agentSessionId) {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir,
                status, createdAt, Instant.now());
    }

    /** Returns a copy with a new title and a refreshed {@code updatedAt}. */
    public ChatSession withTitle(String title) {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir,
                status, createdAt, Instant.now());
    }

    /** Returns a copy with a refreshed {@code updatedAt}. */
    public ChatSession touched() {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir,
                status, createdAt, Instant.now());
    }
}
