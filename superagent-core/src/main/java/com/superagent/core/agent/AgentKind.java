package com.superagent.core.agent;

/** What role an agent plays for its owner. */
public enum AgentKind {
    /** An ordinary agent: created by the user, or by their SuperAgent as a team member. */
    STANDARD,
    /** The user's built-in leader: one per user, can't be deleted, plans work and runs the team. */
    SUPER
}
