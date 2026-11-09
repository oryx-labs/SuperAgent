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
        Instant updatedAt,
        String model,
        String reason,
        int cliRestarts) {

    // Why an agent's session was opened, shown to its owner in the run log.
    public static final String FIRST = "first";            // the agent's first conversation
    public static final String MODEL_CHANGED = "model";    // conversations moved to another model
    public static final String MODEL_DOWN = "model_down";  // its model couldn't run, so the turn moved to another
    public static final String FAILURES = "failures";      // the session kept failing

    /** A session outside an agent's history (ad-hoc chats, detached runs): no model or reason kept. */
    public ChatSession(String id, String title, AgentType agentType, String agentSessionId, String workspaceDir,
                       String agentId, SessionStatus status, Instant createdAt, Instant updatedAt) {
        this(id, title, agentType, agentSessionId, workspaceDir, agentId, status, createdAt, updatedAt, null, null, 0);
    }

    /**
     * Returns a copy with the agent session id and a refreshed {@code updatedAt}. A different id for a session that
     * already had one means the CLI couldn't resume its conversation and started over inside this session.
     */
    public ChatSession withAgentSessionId(String agentSessionId) {
        int restarts = this.agentSessionId != null && !this.agentSessionId.equals(agentSessionId) ? cliRestarts + 1 : cliRestarts;
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now(), model, reason, restarts);
    }

    /** Returns a copy on another CLI (a model switch to the other CLI keeps the session). */
    public ChatSession withAgentType(AgentType agentType, String agentSessionId) {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now(), model, reason, cliRestarts);
    }

    /** Returns a copy with a new title and a refreshed {@code updatedAt}. */
    public ChatSession withTitle(String title) {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now(), model, reason, cliRestarts);
    }

    /** Returns a copy with a refreshed {@code updatedAt}. */
    public ChatSession touched() {
        return new ChatSession(id, title, agentType, agentSessionId, workspaceDir, agentId,
                status, createdAt, Instant.now(), model, reason, cliRestarts);
    }
}
