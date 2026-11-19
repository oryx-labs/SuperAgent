package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.RunCredential;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MiniMax Code: {@code mcode exec --output-format stream-json}. Key and MCP live in an isolated
 * {@code MINIMAX_DATA_DIR} (config.yaml + mcp.json) so the operator's default data dir is untouched.
 */
@Component
public class MinimaxCliAgentRunner extends JsonLineCliRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL = "minimax/MiniMax-M3.1-Flash";

    public MinimaxCliAgentRunner(@Value("${superagent.agent.minimax.command:mcode}") String command,
                                 @Value("${superagent.agent.minimax.timeout-seconds:600}") long timeoutSeconds) {
        super(command, timeoutSeconds);
    }

    @Override
    public AgentType type() {
        return AgentType.MINIMAX;
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        Path dataDir = dataDir(request);
        Files.createDirectories(dataDir);
        writeHome(dataDir, request);
        List<String> cmd = new ArrayList<>(List.of(command(), "exec",
                "--output-format", "stream-json",
                "--permission", permission(request),
                "--cwd", request.workspaceDir()));
        String model = modelArg(request.model());
        if (model != null) {
            cmd.addAll(List.of("--model", model));
        }
        if (resumeId != null) {
            cmd.addAll(List.of("--session", resumeId));
        } else if (request.systemPromptWithMemory() != null && !request.systemPromptWithMemory().isBlank()) {
            cmd.addAll(List.of("--append-system-prompt", request.systemPromptWithMemory()));
        }
        cmd.add(request.prompt());
        return cmd;
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return null; // prompt is an argv operand
    }

    /** skip-permissions → full; writable allowlist → smart; otherwise off (ask needs a TUI). */
    static String permission(AgentRunRequest request) {
        if (request.skipPermissions()) {
            return "full";
        }
        return canWrite(request) ? "smart" : "off";
    }

    /** {@code MiniMax-M2.1} → {@code minimax/MiniMax-M2.1}; already-qualified ids pass through. */
    static String modelArg(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        String m = model.strip();
        return m.contains("/") ? m : "minimax/" + m;
    }

    /**
     * User-level mcp.json under {@code MINIMAX_DATA_DIR}: remote HTTP MCP as {@code type: http}
     * (streamable-http also accepted by Runtime).
     */
    static String mcpJson(List<CodexCliAgentRunner.McpServer> servers) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode all = root.putObject("mcpServers");
        for (CodexCliAgentRunner.McpServer s : servers) {
            ObjectNode server = all.putObject(s.name());
            server.put("type", "http");
            server.put("url", s.url());
            if (s.token() != null) {
                server.putObject("headers").put("Authorization", "Bearer " + s.token());
            }
        }
        return root.toString();
    }

    /** Minimal config.yaml so exec does not need an interactive {@code set-minimax-key}. */
    static String configYaml(String apiKey, String defaultModel) {
        String model = defaultModel == null || defaultModel.isBlank() ? DEFAULT_MODEL : defaultModel;
        return "logLevel: info\n"
                + "defaultModel: " + yamlString(model) + "\n"
                + "minimax_api:\n"
                + "  apiKey: " + yamlString(apiKey) + "\n"
                + "minimaxModelSource: minimax_api_key\n";
    }

    private void writeHome(Path dataDir, AgentRunRequest request) throws IOException {
        writePrivate(dataDir.resolve("config.yaml"),
                configYaml(request.credential().apiKey(), modelArg(request.model())));
        writePrivate(dataDir.resolve("mcp.json"), mcpJson(CodexCliAgentRunner.mcpServers(request)));
    }

    static Path dataDir(AgentRunRequest request) {
        return Path.of(request.workspaceDir(), ".superagent", "mcode-home", request.sessionId());
    }

    static Map<String, String> environment(RunCredential credential, Path dataDir) {
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_DATA_DIR", dataDir.toString());
        if (credential != null) {
            env.put("MCODE_PROVIDER_API_KEY", credential.apiKey());
            if (credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
                env.put("MCODE_API_BASE_URL", credential.baseUrl().strip());
            }
        }
        return env;
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) {
        return environment(request.credential(), dataDir(request));
    }

    @Override
    protected LineParser newParser() {
        return new McodeStreamParser();
    }

    private static void writePrivate(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX filesystem
        }
    }

    private static String yamlString(String value) {
        // Quote so keys/models with special chars stay literal.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
