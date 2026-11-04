package com.superagent.core.agent;

import java.util.Optional;

/** Persistence SPI for {@link AgentMemoryState}, one row per agent. The active backend is chosen in superagent-storage. */
public interface AgentMemoryStateStore {

    Optional<AgentMemoryState> find(String agentId);

    /** Inserts or replaces by agent id. */
    AgentMemoryState save(AgentMemoryState state);

    void delete(String agentId);
}
