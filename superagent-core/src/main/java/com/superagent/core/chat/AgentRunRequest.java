package com.superagent.core.chat;

import java.util.List;

/**
 * One agent turn to run. {@code agentSessionId} is null on the first turn (the
 * agent mints a new session) and set thereafter to resume it; {@code skipPermissions}
 * bypasses all permission prompts and is only safe in a trusted sandbox.
 * {@code mcpConfig} is an optional MCP-servers JSON config to expose to the agent.
 * {@code systemPrompt} is an optional persona appended to the agent's system
 * prompt (Claude CLI {@code --append-system-prompt}); null means none.
 * {@code images} are attached to this turn's prompt only; never null.
 *
 * <p>{@code memory} is the agent's long-term memory (Markdown), or null. A runner adds it only
 * when the CLI starts a <em>new</em> conversation — a resumed one already carries its history —
 * including when it has to start over because the conversation to resume is gone.
 */
public record AgentRunRequest(
        String sessionId,
        String agentSessionId,
        String workspaceDir,
        String prompt,
        String model,
        List<String> allowedTools,
        boolean skipPermissions,
        String mcpConfig,
        String systemPrompt,
        List<ImageAttachment> images,
        String memory) {

    public AgentRunRequest {
        images = images == null ? List.of() : List.copyOf(images);
        memory = memory == null || memory.isBlank() ? null : memory.strip();
    }

    /** A turn without long-term memory. */
    public AgentRunRequest(String sessionId, String agentSessionId, String workspaceDir, String prompt, String model,
                           List<String> allowedTools, boolean skipPermissions, String mcpConfig, String systemPrompt,
                           List<ImageAttachment> images) {
        this(sessionId, agentSessionId, workspaceDir, prompt, model, allowedTools, skipPermissions, mcpConfig,
                systemPrompt, images, null);
    }

    /** A text-only turn. */
    public AgentRunRequest(String sessionId, String agentSessionId, String workspaceDir, String prompt, String model,
                           List<String> allowedTools, boolean skipPermissions, String mcpConfig, String systemPrompt) {
        this(sessionId, agentSessionId, workspaceDir, prompt, model, allowedTools, skipPermissions, mcpConfig,
                systemPrompt, List.of(), null);
    }

    /** True when this is the first turn and the agent should mint a new session. */
    public boolean isFirstTurn() {
        return agentSessionId == null || agentSessionId.isBlank();
    }

    /**
     * The system prompt for a conversation the CLI is starting fresh: the persona plus, when there is
     * one, the agent's memory, framed so the model treats it as background rather than instructions.
     */
    public String systemPromptWithMemory() {
        if (memory == null) {
            return systemPrompt;
        }
        String block = "## 你的长期记忆（从以往会话中提炼）\n" + memory
                + "\n\n（以上是对你与用户以往对话的提炼，可能不完整；若与用户最新的说法冲突，以用户为准。）";
        return systemPrompt == null || systemPrompt.isBlank() ? block : systemPrompt + "\n\n" + block;
    }
}
