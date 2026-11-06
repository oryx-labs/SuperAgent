package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kimi Code: {@code kimi -p … --output-format stream-json}. Kimi reads its key and model only from config.toml, so
 * runs use a home of their own (not the machine's {@code ~/.kimi-code}) whose config is rewritten before each run.
 */
@Component
public class KimiCliAgentRunner extends JsonLineCliRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_CONTEXT = 262144;

    private final Path home;

    public KimiCliAgentRunner(@Value("${superagent.agent.kimi.command:kimi}") String command,
                              @Value("${superagent.agent.kimi.timeout-seconds:600}") long timeoutSeconds,
                              @Value("${superagent.agent.kimi.home:./data/runtime/kimi}") String home) {
        super(command, timeoutSeconds);
        this.home = Path.of(home).toAbsolutePath().normalize();
    }

    @Override
    public AgentType type() {
        return AgentType.KIMI;
    }

    @Override
    protected String precondition(AgentRunRequest request) {
        String problem = super.precondition(request);
        return problem != null || (request.model() != null && !request.model().isBlank()) ? problem
                : "Kimi Code 需要指定模型：请在对话框或任务里选一个 Kimi 模型";
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        writeHome(request);
        List<String> cmd = new ArrayList<>(List.of(command(), "-p", prompt(request, resumeId), "--output-format", "stream-json"));
        if (resumeId != null) {
            cmd.addAll(List.of("-S", resumeId));
        }
        return cmd;
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return null; // -p
    }

    private synchronized void writeHome(AgentRunRequest request) throws IOException {
        Files.createDirectories(home);
        writePrivate(home.resolve("config.toml"), config(request.model(), request.credential().baseUrl(), request.credential().apiKey()));
        List<CodexCliAgentRunner.McpServer> servers = CodexCliAgentRunner.mcpServers(request);
        ObjectNode root = JSON.createObjectNode();
        ObjectNode all = root.putObject("mcpServers");
        for (CodexCliAgentRunner.McpServer s : servers) {
            ObjectNode server = all.putObject(s.name());
            server.put("url", s.url());
            if (s.token() != null) {
                server.putObject("headers").put("Authorization", "Bearer " + s.token());
            }
        }
        writePrivate(home.resolve("mcp.json"), root.toString());
    }

    static String config(String model, String baseUrl, String apiKey) throws IOException {
        String alias = "superagent/" + model;
        return "default_model = " + JSON.writeValueAsString(alias) + "\n\n"
                + "[providers.\"superagent\"]\ntype = \"kimi\"\n"
                + "base_url = " + JSON.writeValueAsString(baseUrl == null ? "https://api.moonshot.cn/v1" : baseUrl) + "\n"
                + "api_key = " + JSON.writeValueAsString(apiKey) + "\n\n"
                + "[models." + JSON.writeValueAsString(alias) + "]\nprovider = \"superagent\"\n"
                + "model = " + JSON.writeValueAsString(model) + "\nmax_context_size = " + MAX_CONTEXT + "\n";
    }

    private static void writePrivate(Path file, String content) throws IOException {
        Files.writeString(file, content);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX filesystem
        }
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) {
        return Map.of("KIMI_CODE_HOME", home.toString());
    }

    @Override
    protected LineParser newParser() {
        return new Parser();
    }

    /** Whole messages by role, no deltas; the last assistant text is the result. */
    static final class Parser implements LineParser {
        private String last;

        @Override
        public List<Line> parse(String line) {
            JsonNode e;
            try {
                e = JSON.readTree(line);
            } catch (IOException ex) {
                return List.of();
            }
            List<Line> out = new ArrayList<>();
            switch (e.path("role").asText("")) {
                case "meta" -> {
                    if ("session.resume_hint".equals(e.path("type").asText())) {
                        out.add(new Line(AgentEventType.SYSTEM, e.path("session_id").asText()));
                    }
                }
                case "assistant" -> {
                    String text = text(e.path("content"));
                    if (!text.isBlank()) {
                        last = text;
                        out.add(new Line(AgentEventType.TEXT, text));
                    }
                    for (JsonNode call : e.path("tool_calls")) {
                        out.add(new Line(AgentEventType.TOOL_USE, call.path("function").path("name").asText(call.path("name").asText("tool"))));
                    }
                }
                case "tool" -> out.add(new Line(AgentEventType.TOOL_RESULT, text(e.path("content"))));
                default -> { /* nothing to show */ }
            }
            return out;
        }

        @Override
        public List<Line> finish() {
            return last == null ? List.of() : List.of(new Line(AgentEventType.RESULT, last));
        }

        private static String text(JsonNode content) {
            if (content.isTextual()) {
                return content.asText();
            }
            StringBuilder sb = new StringBuilder();
            content.forEach(block -> sb.append(block.path("text").asText("")));
            return sb.toString();
        }
    }
}
