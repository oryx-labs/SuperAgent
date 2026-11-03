package com.superagent.core.agent;

/** Lifecycle state of an {@link Agent}. DELETED is a soft delete: the row stays, the UI hides it. */
public enum AgentStatus {
    ACTIVE,
    DELETED
}
