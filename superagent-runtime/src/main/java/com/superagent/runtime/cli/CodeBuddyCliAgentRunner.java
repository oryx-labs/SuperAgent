package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentType;
import com.superagent.core.settings.SystemSettingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tencent CodeBuddy Code: {@code codebuddy -p --output-format stream-json}. Output matches Claude Code's shape,
 * so {@link ClaudeStreamParser} reads it. MCP is Claude-style {@code --mcp-config} + {@code --strict-mcp-config};
 * the key and region travel only in the process environment.
 */
@Component
public class CodeBuddyCliAgentRunner extends JsonLineCliRunner {

    /** Company setting: {@code internal} (default), {@code public}, {@code ioa}, {@code cloudhosted}, {@code selfhosted}. */
    public static final String INTERNET_ENV_SETTING = "codebuddy.internet-environment";
    public static final String DEFAULT_INTERNET_ENV = "internal";
    private static final Set<String> INTERNET_ENVS = Set.of("internal", "public", "ioa", "cloudhosted", "selfhosted");

    private static final ObjectMapper JSON = new ObjectMapper();

    private SystemSettingStore settings; // null in tests: default region (internal)

    public CodeBuddyCliAgentRunner(@Value("${superagent.agent.codebuddy.command:codebuddy}") String command,
                                   @Value("${superagent.agent.codebuddy.timeout-seconds:600}") long timeoutSeconds) {
        super(command, timeoutSeconds);
    }

    @Autowired(required = false)
    void setSettings(SystemSettingStore settings) {
        this.settings = settings;
    }

    @Override
    public AgentType type() {
        return AgentType.CODEBUDDY;
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(command(), "-p", "--output-format", "stream-json",
                "--include-partial-messages"));
        if (request.model() != null && !request.model().isBlank()) {
            cmd.addAll(List.of("--model", request.model()));
        }
        if (resumeId != null) {
            cmd.addAll(List.of("--resume", resumeId));
        } else if (request.systemPromptWithMemory() != null && !request.systemPromptWithMemory().isBlank()) {
            cmd.addAll(List.of("--append-system-prompt", request.systemPromptWithMemory()));
        }
        // Headless print mode needs an explicit permission policy or the CLI blocks tool use.
        if (request.skipPermissions()) {
            cmd.add("-y");
        } else if (request.allowedTools() != null && !request.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(" ", request.allowedTools()));
        } else {
            cmd.add("-y");
        }
        String mcp = mcpConfig(request);
        if (mcp != null) {
            Path file = Files.createTempFile("superagent-codebuddy-mcp-", ".json");
            cleanup.add(file);
            Files.writeString(file, mcp);
            cmd.addAll(List.of("--strict-mcp-config", "--mcp-config", file.toString()));
        }
        return cmd;
    }

    /**
     * Claude-shaped MCP JSON (type/url/headers). CodeBuddy's {@code --mcp-config} accepts the same shape;
     * SuperAgent already builds it that way in {@code McpGrants}.
     */
    static String mcpConfig(AgentRunRequest request) {
        if (request.mcpConfig() == null || request.mcpConfig().isBlank()) {
            return null;
        }
        return request.mcpConfig();
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return request.prompt(); // system prompt has its own flag on a fresh session
    }

    /** Env for a CodeBuddy process: key, optional base URL, and region. */
    static Map<String, String> environment(com.superagent.core.chat.RunCredential credential, String internetEnv) {
        Map<String, String> env = new HashMap<>();
        if (credential != null) {
            env.put("CODEBUDDY_API_KEY", credential.apiKey());
            if (credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
                env.put("CODEBUDDY_BASE_URL", credential.baseUrl().strip());
            }
        }
        String region = normalizeInternetEnvironment(internetEnv);
        if (!"public".equals(region)) {
            env.put("CODEBUDDY_INTERNET_ENVIRONMENT", region);
        }
        return env;
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) {
        Map<String, String> env = environment(request.credential(), internetEnvironment());
        if (request.skipPermissions()) {
            // Full pass for HIGH/CRITICAL only when the launcher declares an isolated sandbox.
            env.put("CODEBUDDY_IS_SANDBOX", "1");
        }
        return env;
    }

    private String internetEnvironment() {
        if (settings == null) {
            return DEFAULT_INTERNET_ENV;
        }
        String v = settings.all().get(INTERNET_ENV_SETTING);
        return v == null || v.isBlank() ? DEFAULT_INTERNET_ENV : v.strip();
    }

    /** Normalises a Settings value; unknown → default (China {@code internal}). */
    public static String normalizeInternetEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_INTERNET_ENV;
        }
        String v = value.strip().toLowerCase();
        return INTERNET_ENVS.contains(v) ? v : DEFAULT_INTERNET_ENV;
    }

    @Override
    protected LineParser newParser() {
        ClaudeStreamParser claude = new ClaudeStreamParser();
        return new LineParser() {
            private com.fasterxml.jackson.databind.JsonNode usage;

            @Override
            public List<Line> parse(String line) {
                if (line.contains("\"result\"")) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(line);
                        if ("result".equals(root.path("type").asText()) && root.path("usage").isObject()) {
                            usage = root.path("usage");
                        }
                    } catch (Exception ignored) {
                        // not JSON: the Claude parser skips it too
                    }
                }
                return claude.parse(line).map(e -> List.of(new Line(e.type(), e.payload()))).orElse(List.of());
            }

            @Override
            public List<com.superagent.core.usage.TokenUsage> tokens(String model) {
                // Same field split as Claude Code (input and cache are separate, not a total).
                return usage == null ? List.of()
                        : List.of(new com.superagent.core.usage.TokenUsage(model, Tokens.n(usage, "input_tokens"),
                                Tokens.n(usage, "cache_read_input_tokens"), Tokens.n(usage, "cache_creation_input_tokens"),
                                0, Tokens.n(usage, "output_tokens"), 0, null));
            }
        };
    }
}
