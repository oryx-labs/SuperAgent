package com.superagent.core.chat;

import java.util.List;

/**
 * One agent turn to run. {@code agentSessionId} is null on the first turn (the
 * agent mints a new session) and set thereafter to resume it; {@code skipPermissions}
 * bypasses all permission prompts and is only safe in a trusted sandbox.
 * {@code mcpConfig} is an optional MCP-servers JSON config to expose to the agent.
 */
public record AgentRunRequest(
        String sessionId,
        String agentSessionId,
        String workspaceDir,
        String prompt,
        String model,
        List<String> allowedTools,
        boolean skipPermissions,
        String mcpConfig) {

    /** True when this is the first turn and the agent should mint a new session. */
    public boolean isFirstTurn() {
        return agentSessionId == null || agentSessionId.isBlank();
    }
}
