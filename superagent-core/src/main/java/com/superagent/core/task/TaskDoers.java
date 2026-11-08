package com.superagent.core.task;

import java.util.Optional;

/**
 * The agent a task runs as: an agent that sets off a task does it itself, so the task carries the agent's
 * character and memory (its model is chosen for the task). Implemented where agents live; tasks only see this.
 */
public interface TaskDoers {

    /** The agent behind {@code agentId}; empty when it's gone or isn't one that does its own tasks. */
    Optional<Doer> doer(String agentId);

    /** Just its name, for lists (no memory read). */
    default Optional<String> name(String agentId) {
        return doer(agentId).map(Doer::name);
    }

    /** {@code character}: who it is and how it works (its system prompt); {@code memory}: what it remembers, or null. */
    record Doer(String name, String character, String memory) {
    }
}
