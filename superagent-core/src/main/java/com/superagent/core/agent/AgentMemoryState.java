package com.superagent.core.agent;

import java.time.Instant;

/**
 * An agent's long-term memory ({@code memory}, Markdown in {@code ## category} sections; null = nothing worth
 * remembering yet) and the bookkeeping around it and the agent's current session.
 * {@code summarizedUntil}: messages created at or before this are already distilled into the memory
 * file (null = nothing yet). {@code lastModel}: the model the current session talks to; a different
 * model means a new session. {@code sessionFailures}: consecutive failed turns on the current session
 * (network-type errors don't count); at the limit the agent moves to a fresh session.
 * {@code agentSections}: categories the agent wrote itself (one per line), which distilling only adds to;
 * {@code writtenBy}/{@code writtenSession}/{@code writtenTask}/{@code writtenAt}: its last such write.
 */
public record AgentMemoryState(
        String agentId,
        String owner,
        Instant summarizedUntil,
        String lastModel,
        int sessionFailures,
        String memory,
        Instant updatedAt,
        String agentSections,
        String writtenBy,
        String writtenSession,
        String writtenTask,
        Instant writtenAt) {

    public AgentMemoryState(String agentId, String owner, Instant summarizedUntil, String lastModel, int sessionFailures,
                            String memory, Instant updatedAt) {
        this(agentId, owner, summarizedUntil, lastModel, sessionFailures, memory, updatedAt, null, null, null, null, null);
    }

    public AgentMemoryState summarizedTo(Instant until) {
        return new AgentMemoryState(agentId, owner, until, lastModel, sessionFailures, memory, Instant.now(),
                agentSections, writtenBy, writtenSession, writtenTask, writtenAt);
    }

    public AgentMemoryState withLastModel(String model) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, model, sessionFailures, memory, Instant.now(),
                agentSections, writtenBy, writtenSession, writtenTask, writtenAt);
    }

    public AgentMemoryState withSessionFailures(int failures) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, lastModel, failures, memory, Instant.now(),
                agentSections, writtenBy, writtenSession, writtenTask, writtenAt);
    }

    public AgentMemoryState withMemory(String newMemory) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, lastModel, sessionFailures, newMemory, Instant.now(),
                agentSections, writtenBy, writtenSession, writtenTask, writtenAt);
    }

    public AgentMemoryState withOwner(String newOwner) {
        return new AgentMemoryState(agentId, newOwner, summarizedUntil, lastModel, sessionFailures, memory, Instant.now(),
                agentSections, writtenBy, writtenSession, writtenTask, writtenAt);
    }

    /** A person's own edit: what they leave is theirs, so no category is held for the agent any more. */
    public AgentMemoryState editedByPerson(String newMemory) {
        return new AgentMemoryState(agentId, owner, summarizedUntil, lastModel, sessionFailures, newMemory, Instant.now(),
                null, writtenBy, writtenSession, writtenTask, writtenAt);
    }

    public AgentMemoryState writtenByAgent(String newMemory, String sections, String by, String sessionId, String taskId) {
        Instant now = Instant.now();
        return new AgentMemoryState(agentId, owner, summarizedUntil, lastModel, sessionFailures, newMemory, now,
                sections, by, sessionId, taskId, now);
    }
}
