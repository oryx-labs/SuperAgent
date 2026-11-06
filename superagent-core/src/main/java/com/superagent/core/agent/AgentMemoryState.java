package com.superagent.core.agent;

import java.time.Instant;

/**
 * An agent's long-term memory ({@code memory}, Markdown; null = nothing worth remembering yet) and the
 * bookkeeping around it and the agent's current session.
 * {@code summarizedUntil}: messages created at or before this are already distilled into the memory
 * file (null = nothing yet). {@code lastModel}: the model the current session talks to; a different
 * model means a new session. {@code sessionFailures}: consecutive failed turns on the current session
 * (network-type errors don't count); at the limit the agent moves to a fresh session.
 */
public record AgentMemoryState(
        String agentId,
        String owner,
        Instant summarizedUntil,
        String lastModel,
        int sessionFailures,
        String memory,
        Instant updatedAt) {

    public AgentMemoryState summarizedTo(Instant until) {
        return new AgentMemoryState(agentId, owner, until, lastModel, sessionFailures, memory, Instant.now());
    }

    public AgentMemoryState withLastModel(String model) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, model, sessionFailures, memory, Instant.now());
    }

    public AgentMemoryState withSessionFailures(int failures) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, lastModel, failures, memory, Instant.now());
    }

    public AgentMemoryState withMemory(String newMemory) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, lastModel, sessionFailures, newMemory, Instant.now());
    }
}
