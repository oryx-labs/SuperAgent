package com.superagent.core.agent;

import java.time.Instant;
import java.util.List;

/** Persistence SPI for agents' run logs (append-only). The active backend is chosen in superagent-storage. */
public interface AgentLogStore {

    void append(AgentLogEntry entry);

    /** Newest first, at most {@code limit}; {@code category} null = all. */
    List<AgentLogEntry> list(String agentId, String category, int limit);

    /** Drops lines older than {@code before}, and for each agent everything beyond its newest {@code keepPerAgent}. */
    void prune(Instant before, int keepPerAgent);

    void deleteAgent(String agentId);
}
