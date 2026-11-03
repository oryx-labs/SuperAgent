package com.superagent.core.chat;

/**
 * A CLI coding agent that SuperAgent can drive. The platform is agent-agnostic:
 * each value maps to an {@link AgentRunner} implementation that knows how to
 * invoke that agent and stream its output.
 */
public enum AgentType {

    CLAUDE("claude"),
    CODEX("codex"),
    KIMI("kimi");

    private final String cliName;

    AgentType(String cliName) {
        this.cliName = cliName;
    }

    /** The executable / identifier used on the command line. */
    public String cliName() {
        return cliName;
    }

    public static AgentType fromString(String value) {
        if (value == null || value.isBlank()) {
            return CLAUDE;
        }
        return AgentType.valueOf(value.trim().toUpperCase());
    }
}
