package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qwen Code: {@code qwen -o stream-json}, whose output has the same shape as Claude Code's, so its parser does.
 * It reaches any OpenAI-compatible endpoint through {@code OPENAI_*}.
 */
@Component
public class QwenCliAgentRunner extends JsonLineCliRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    public QwenCliAgentRunner(@Value("${superagent.agent.qwen.command:qwen}") String command,
                              @Value("${superagent.agent.qwen.timeout-seconds:600}") long timeoutSeconds) {
        super(command, timeoutSeconds);
    }

    @Override
    public AgentType type() {
        return AgentType.QWEN;
    }

    @Override
    protected String precondition(AgentRunRequest request) {
        String problem = super.precondition(request);
        return problem != null || (request.model() != null && !request.model().isBlank()) ? problem
                : "Qwen Code 需要指定模型：请在对话框或任务里选一个 Qwen 模型";
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(command(), "-o", "stream-json", "--include-partial-messages",
                "--approval-mode", canWrite(request) ? "yolo" : "plan", "-m", request.model()));
        if (resumeId != null) {
            cmd.addAll(List.of("-r", resumeId));
        } else if (request.systemPromptWithMemory() != null) {
            cmd.addAll(List.of("--append-system-prompt", request.systemPromptWithMemory()));
        }
        List<CodexCliAgentRunner.McpServer> servers = CodexCliAgentRunner.mcpServers(request);
        if (!servers.isEmpty()) {
            Path file = Files.createTempFile("superagent-qwen-mcp-", ".json");
            cleanup.add(file);
            Files.writeString(file, mcpConfig(servers));
            cmd.addAll(List.of("--mcp-config", file.toString()));
        }
        return cmd;
    }

    /** Qwen's MCP config takes streamable-HTTP servers under {@code httpUrl}. */
    static String mcpConfig(List<CodexCliAgentRunner.McpServer> servers) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode all = root.putObject("mcpServers");
        for (CodexCliAgentRunner.McpServer s : servers) {
            ObjectNode server = all.putObject(s.name());
            server.put("httpUrl", s.url());
            server.put("trust", true); // SuperAgent's own, scoped to this turn
            if (s.token() != null) {
                server.putObject("headers").put("Authorization", "Bearer " + s.token());
            }
        }
        return root.toString();
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return request.prompt(); // the system prompt has its own flag here
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) {
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", request.credential().apiKey());
        env.put("OPENAI_MODEL", request.model());
        if (request.credential().baseUrl() != null) {
            env.put("OPENAI_BASE_URL", request.credential().baseUrl());
        }
        return env;
    }

    @Override
    protected LineParser newParser() {
        ClaudeStreamParser claude = new ClaudeStreamParser();
        return line -> claude.parse(line).map(e -> List.of(new Line(e.type(), e.payload()))).orElse(List.of());
    }
}
