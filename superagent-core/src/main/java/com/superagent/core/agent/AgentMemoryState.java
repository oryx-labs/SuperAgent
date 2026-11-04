package com.superagent.core.agent;

import java.time.Instant;

/**
 * Per-agent bookkeeping for long-term memory. {@code summarizedUntil}: messages created at or
 * before this are already distilled into the memory file (null = nothing yet). {@code lastModel}:
 * the model the agent's current session talks to; a different model means a new session.
 */
public record AgentMemoryState(
        String agentId,
        String owner,
        Instant summarizedUntil,
        String lastModel,
        Instant updatedAt) {

    public AgentMemoryState summarizedTo(Instant until) {
        return new AgentMemoryState(agentId, owner, until, lastModel, Instant.now());
    }

    public AgentMemoryState withLastModel(String model) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, model, Instant.now());
    }
}
