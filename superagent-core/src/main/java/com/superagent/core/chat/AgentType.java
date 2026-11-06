package com.superagent.core.chat;

/**
 * A CLI coding agent that SuperAgent can drive. The platform is agent-agnostic:
 * each value maps to an {@link AgentRunner} implementation that knows how to
 * invoke that agent and stream its output.
 */
public enum AgentType {

    CLAUDE("claude"),
    CODEX("codex"),
    KIMI("kimi"),
    DEEPSEEK("deepseek"),
    QWEN("qwen"),
    /** Zhipu has no agent CLI: Claude Code, pointed at GLM's Anthropic-compatible endpoint. */
    GLM("glm");

    private final String cliName;

    AgentType(String cliName) {
        this.cliName = cliName;
    }

    /** The agent's identifier: its key in the provider catalogue (for most, also the executable's name). */
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
