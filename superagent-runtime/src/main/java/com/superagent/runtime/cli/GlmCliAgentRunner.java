package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * GLM: Zhipu ships no agent CLI, so this is Claude Code pointed at Zhipu's Anthropic-compatible endpoint. Without a
 * key there's no running it: Claude Code would quietly use the machine's Anthropic login and fail on a GLM model.
 * It runs with a config directory of its own: the machine's {@code ~/.claude/settings.json} carries the Claude
 * pool's endpoint and key in its {@code env}, which Claude Code lets win over the process environment.
 */
@Component
public class GlmCliAgentRunner extends ClaudeCliAgentRunner {

    private final java.nio.file.Path configDir;

    public GlmCliAgentRunner(@Value("${superagent.agent.claude.command:claude}") String command,
                             @Value("${superagent.agent.claude.timeout-seconds:600}") long timeoutSeconds,
                             @Value("${superagent.agent.glm.config-dir:./data/runtime/glm}") String configDir) {
        super(command, timeoutSeconds);
        this.configDir = java.nio.file.Path.of(configDir).toAbsolutePath().normalize();
    }

    @Override
    protected void applyEnvironment(java.lang.ProcessBuilder pb, AgentRunRequest request) {
        super.applyEnvironment(pb, request);
        try {
            java.nio.file.Files.createDirectories(configDir);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        pb.environment().put("CLAUDE_CONFIG_DIR", configDir.toString());
    }

    @Override
    public AgentType type() {
        return AgentType.GLM;
    }

    @Override
    public void run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
        if (request.credential() == null) {
            sink.emit(new AgentEvent(request.sessionId(), null, 0, AgentEventType.ERROR,
                    "还没有配置 GLM 的 API Key：请到 Models 页的 API Key 里添加", Instant.now()));
            return;
        }
        super.run(request, sink, cancellation);
    }
}
