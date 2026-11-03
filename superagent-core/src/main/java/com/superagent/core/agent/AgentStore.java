package com.superagent.core.agent;

import java.util.List;
import java.util.Optional;

/**
 * Persistence SPI for {@link Agent} metadata, mirroring the session part of
 * {@code ChatStore}. The active backend is chosen in {@code superagent-storage}.
 * Soft delete is applied through {@link #update(Agent)} with a DELETED status.
 */
public interface AgentStore {

    Agent create(Agent agent);

    Optional<Agent> find(String id);

    List<Agent> list();

    /** Replaces the stored agent metadata for {@code agent.id()}. */
    void update(Agent agent);
}
