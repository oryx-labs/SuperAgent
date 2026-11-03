package com.superagent.core.chat;

import java.time.Instant;

/**
 * A SuperAgent conversation, mapped one-to-one onto a CLI agent session:
 * {@code agentSessionId} holds the agent's own session id (e.g. the Claude Code
 * session UUID) so follow-up turns resume the same conversation, and {@code id}
 * doubles as that session id on the first turn.
 *
 * <p>{@code agentId} is the owning Agent (persona), nullable: legacy sessions and
 * ad-hoc chats have none. Phase 1 is one agent to one session; the field models
 * "a session belongs to an agent" so phase 2 can attach many without a rewrite.
 */
public record ChatSession(
        String id,
        String title,
        AgentType agentType,
        String agentSessionId,
        String workspaceDir,
        String agentId,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** Returns a copy with the agent session id and a refreshed {@code updatedAt}. */
    public ChatSession withAgentSessionId(String agentSessionId) {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now());
    }

    /** Returns a copy with a new title and a refreshed {@code updatedAt}. */
    public ChatSession withTitle(String title) {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now());
    }

    /** Returns a copy with a refreshed {@code updatedAt}. */
    public ChatSession touched() {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now());
    }
}
