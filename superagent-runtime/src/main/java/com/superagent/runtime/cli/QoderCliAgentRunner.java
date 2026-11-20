package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Alibaba Qoder CN: {@code qodercli -p --output-format stream-json}. The CLI is a Claude Code clone — the same flags
 * ({@code --model}, {@code --resume}, {@code --append-system-prompt}, {@code --allowed-tools}, {@code --mcp-config},
 * {@code --permission-mode}) and the same stream-json events — so {@link ClaudeStreamParser} reads its output.
 *
 * <p>The CN site is picked with {@code QWEN_QODER_REGION=cn} and authenticates with a personal access token in
 * {@code QODERCN_PERSONAL_ACCESS_TOKEN}: with it set the CLI runs headless (its "qoder-pat" mode) instead of opening
 * a browser for a login. The token travels in the environment, never in argv, and the CLI's own config lives in an
 * isolated directory per session, so the operator's {@code ~/.qoder-cn} stays untouched. {@code NO_BROWSER=1} keeps a
 * missing token from hanging the run on a login prompt.
 */
@Component
public class QoderCliAgentRunner extends JsonLineCliRunner {

    /**
     * The personal access token — the key this provider pools. The CN CLI reads it from
     * {@code QODERCN_PERSONAL_ACCESS_TOKEN} and runs headless (no browser) when it is set.
     */
    public static final String PAT_ENV = "QODERCN_PERSONAL_ACCESS_TOKEN";
    /** The CN CLI's user config root; pointed at an isolated directory so the operator's own stays untouched. */
    public static final String CONFIG_DIR_ENV = "QODERCN_CONFIG_DIR";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QoderCliAgentRunner.class);

    public QoderCliAgentRunner(@Value("${superagent.agent.qoder.command:qoderclicn}") String command,
                                @Value("${superagent.agent.qoder.timeout-seconds:600}") long timeoutSeconds) {
        super(command, timeoutSeconds);
    }

    @Override
    public AgentType type() {
        return AgentType.QODER;
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
        // The CLI's own permission model: full pass only when asked for, otherwise the allowlist it was given
        // (the CN CLI takes the allowlist comma-separated).
        if (request.skipPermissions()) {
            log.warn("session {} starting with --yolo: the agent may run any tool without approval. Enable "
                    + "superagent.agent.skip-permissions only in an isolated sandbox.", request.sessionId());
            cmd.add("--yolo");
        } else if (request.allowedTools() != null && !request.allowedTools().isEmpty()) {
            cmd.addAll(List.of("--allowed-tools", String.join(",", request.allowedTools())));
        }
        String mcp = request.mcpConfig();
        if (mcp != null && !mcp.isBlank()) {
            Path file = Files.createTempFile("superagent-qoder-mcp-", ".json");
            cleanup.add(file);
            Files.writeString(file, mcp);
            cmd.addAll(List.of("--mcp-config", file.toString(), "--strict-mcp-config"));
        }
        cmd.addAll(List.of(request.prompt()));   // -p takes the prompt as its argument
        return cmd;
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return null;   // the prompt is in argv
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("NO_BROWSER", "1");   // a missing token must fail the run, not wait for a browser
        if (request.credential() != null) {
            env.put(PAT_ENV, request.credential().apiKey());
        }
        Path config = configDir(request);
        Files.createDirectories(config);
        env.put(CONFIG_DIR_ENV, config.toString());
        return env;
    }

    /** The CLI's own config, isolated per session so the operator's {@code ~/.qoder-cn} stays untouched. */
    static Path configDir(AgentRunRequest request) {
        return Path.of(request.workspaceDir(), ".superagent", "qoder-home", request.sessionId());
    }

    @Override
    protected LineParser newParser() {
        ClaudeStreamParser claude = new ClaudeStreamParser();
        return new LineParser() {
            private JsonNode usage;

            @Override
            public List<Line> parse(String line) {
                if (line.contains("\"result\"")) {
                    try {
                        JsonNode root = JSON.readTree(line);
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
                return usage == null ? List.of()
                        : List.of(new com.superagent.core.usage.TokenUsage(model, Tokens.n(usage, "input_tokens"),
                                Tokens.n(usage, "cache_read_input_tokens"), Tokens.n(usage, "cache_creation_input_tokens"),
                                0, Tokens.n(usage, "output_tokens"), 0, null));
            }
        };
    }
}
