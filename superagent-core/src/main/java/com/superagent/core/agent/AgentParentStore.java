package com.superagent.core.agent;

import java.util.Map;
import java.util.Optional;

/**
 * Which agent another reports to: its parent agent, chosen by the owner (when hiring it through the SuperAgent, or
 * later). Separate from {@link Agent#managedBy()}, which says the SuperAgent hired it; an agent without a parent sits
 * at the top of its owner's team.
 */
public interface AgentParentStore {

    /** Sets the agent's parent; null removes it. */
    void set(String agentId, String parentId);

    Optional<String> parentOf(String agentId);

    /** Agent id → its parent, for every agent that has one. */
    Map<String, String> all();
}
