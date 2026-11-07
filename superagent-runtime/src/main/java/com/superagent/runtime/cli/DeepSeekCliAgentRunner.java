package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * DeepSeek Harness: {@code dsh --profile headless --json}, the task on stdin. The model and SuperAgent's MCP server
 * are a profile patch written for the run; the MCP token reaches it through the environment.
 */
@Component
public class DeepSeekCliAgentRunner extends JsonLineCliRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MCP_TOKEN_ENV = "SUPERAGENT_MCP_TOKEN";

    public DeepSeekCliAgentRunner(@Value("${superagent.agent.deepseek.command:dsh}") String command,
                                  @Value("${superagent.agent.deepseek.timeout-seconds:600}") long timeoutSeconds) {
        super(command, timeoutSeconds);
    }

    @Override
    public AgentType type() {
        return AgentType.DEEPSEEK;
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(command(), "--profile", "headless"));
        String patch = patch(request);
        if (patch != null) {
            Path file = Files.createTempFile("superagent-dsh-", ".yml");
            cleanup.add(file);
            Files.writeString(file, patch);
            cmd.addAll(List.of("--patch", file.toString()));
        }
        cmd.add("--json");
        if (resumeId != null) {
            cmd.addAll(List.of("--session-id", resumeId));
        }
        cmd.addAll(List.of("--permission-mode", canWrite(request) ? "workspace-write" : "read-only",
                "--approval-policy", "never", "-"));
        return cmd;
    }

    /** The profile overlay for this run, or null when the defaults do. JSON strings are valid YAML scalars. */
    static String patch(AgentRunRequest request) throws IOException {
        StringBuilder yaml = new StringBuilder();
        if (request.model() != null && !request.model().isBlank()) {
            yaml.append("- id: agent-default-model\n  name: '@deepseek-ai/dsh-agent-default-model'\n  config:\n")
                    .append("    provider: deepseek-official\n    model: ")
                    .append(JSON.writeValueAsString(request.model())).append('\n');
        }
        // A patch item edits an existing entry by id; new entries (the MCP clients) go under an insert item.
        List<CodexCliAgentRunner.McpServer> servers = CodexCliAgentRunner.mcpServers(request);
        if (!servers.isEmpty()) {
            yaml.append("- insert:\n");
        }
        for (CodexCliAgentRunner.McpServer server : servers) {
            yaml.append("    - id: mcp-").append(server.name()).append("\n      name: '@deepseek-ai/dsh-mcp-client'\n      config:\n")
                    .append("        serverName: ").append(server.name()).append('\n')
                    .append("        transport: streamable-http\n        url: ").append(JSON.writeValueAsString(server.url())).append('\n');
            if (server.token() != null) {
                yaml.append("        headers:\n          Authorization: !!js '`Bearer ${process.env.").append(MCP_TOKEN_ENV)
                        .append("}`'\n");
            }
        }
        return yaml.isEmpty() ? null : yaml.toString();
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) {
        Map<String, String> env = new HashMap<>();
        env.put("DEEPSEEK_API_KEY", request.credential().apiKey());
        if (request.credential().baseUrl() != null) {
            env.put("DEEPSEEK_BASE_URL", request.credential().baseUrl());
        }
        CodexCliAgentRunner.mcpServers(request).stream().filter(s -> s.token() != null).findFirst()
                .ifPresent(s -> env.put(MCP_TOKEN_ENV, s.token()));
        return env;
    }

    @Override
    protected LineParser newParser() {
        return new LineParser() {
            private final long[] sum = new long[5]; // input, cache read, cache write, output, reasoning

            @Override
            public List<Line> parse(String line) {
                addStepUsage(line, sum);
                return parseLine(line);
            }

            @Override
            public List<com.superagent.core.usage.TokenUsage> tokens(String model) {
                return List.of(new com.superagent.core.usage.TokenUsage(model, sum[0], sum[1], sum[2], 0, sum[3], sum[4], null));
            }
        };
    }

    /**
     * dsh reports each step's usage on its {@code status / step_end} line (retried attempts included), in Anthropic's
     * terms: input excludes the cache, which comes as separate reads and writes (read in its source: totalTokens =
     * input + output + cacheRead + cacheWrite); reasoning is part of output.
     */
    static void addStepUsage(String line, long[] sum) {
        if (!line.contains("step_end")) {
            return;
        }
        try {
            JsonNode e = JSON.readTree(line);
            JsonNode u = e.path("usage");
            if (!"status".equals(e.path("type").asText()) || !"step_end".equals(e.path("phase").asText()) || !u.isObject()) {
                return;
            }
            sum[0] += Tokens.n(u, "inputTokens");
            sum[1] += Tokens.n(u, "cacheReadTokens");
            sum[2] += Tokens.n(u, "cacheWriteTokens");
            sum[3] += Tokens.n(u, "outputTokens");
            sum[4] += Tokens.n(u, "reasoningTokens");
        } catch (IOException ignored) {
            // not JSON: nothing to count
        }
    }

    /** {@code session} carries the id, {@code text} the reply, {@code final} how the run ended. */
    static List<Line> parseLine(String line) {
        JsonNode e;
        try {
            e = JSON.readTree(line);
        } catch (IOException ex) {
            return List.of();
        }
        String type = e.path("type").asText("");
        return switch (type) {
            case "session" -> List.of(new Line(AgentEventType.SYSTEM, e.path("sessionId").asText()));
            case "text" -> List.of(new Line(AgentEventType.TEXT, e.path("text").asText("")));
            case "reasoning", "thinking" -> List.of(new Line(AgentEventType.THINKING, e.path("text").asText("")));
            case "final" -> {
                JsonNode reason = e.path("reason");
                yield "completed".equals(reason.path("kind").asText())
                        ? List.of(new Line(AgentEventType.RESULT, e.path("text").asText("")))
                        : List.of(new Line(AgentEventType.ERROR, reason.path("error").path("message").asText(reason.toString())));
            }
            case "tool_call" -> List.of(new Line(AgentEventType.TOOL_USE, e.path("tool").asText("tool")));
            case "tool_result" -> {
                JsonNode result = e.path("result");
                yield List.of(new Line(AgentEventType.TOOL_RESULT, result.isTextual() ? result.asText() : result.toString()));
            }
            default -> List.of();
        };
    }
}
