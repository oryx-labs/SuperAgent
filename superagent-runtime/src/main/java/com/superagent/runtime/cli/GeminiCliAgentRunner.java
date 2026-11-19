package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.RunCredential;
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
 * Google Gemini CLI: {@code gemini -p … -o stream-json}. No {@code --mcp-config}; MCP is written into an
 * isolated {@code GEMINI_CONFIG_DIR}/settings.json ({@code httpUrl} + headers) so the machine's
 * {@code ~/.gemini} is never touched.
 */
@Component
public class GeminiCliAgentRunner extends JsonLineCliRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    public GeminiCliAgentRunner(@Value("${superagent.agent.gemini.command:gemini}") String command,
                                @Value("${superagent.agent.gemini.timeout-seconds:600}") long timeoutSeconds) {
        super(command, timeoutSeconds);
    }

    @Override
    public AgentType type() {
        return AgentType.GEMINI;
    }

    @Override
    protected List<String> buildCommand(AgentRunRequest request, String resumeId, List<Path> cleanup) throws IOException {
        Path configDir = configDir(request);
        Files.createDirectories(configDir);
        writeSettings(configDir, request);
        List<String> cmd = new ArrayList<>(List.of(command(), "-p", prompt(request, resumeId),
                "--output-format", "stream-json", "--skip-trust",
                "--approval-mode", approvalMode(request)));
        if (request.model() != null && !request.model().isBlank()) {
            cmd.addAll(List.of("--model", request.model()));
        }
        if (resumeId != null) {
            cmd.addAll(List.of("--resume", resumeId));
        }
        return cmd;
    }

    @Override
    protected String stdin(AgentRunRequest request, String resumeId) {
        return null; // -p
    }

    /** yolo when skip-permissions; auto_edit when the allowlist can write; otherwise read-only plan. */
    static String approvalMode(AgentRunRequest request) {
        if (request.skipPermissions()) {
            return "yolo";
        }
        return canWrite(request) ? "auto_edit" : "plan";
    }

    /**
     * Gemini's settings.json MCP shape uses {@code httpUrl} (streamable HTTP) plus headers — same idea as Qwen.
     */
    static String settingsJson(List<CodexCliAgentRunner.McpServer> servers) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode all = root.putObject("mcpServers");
        for (CodexCliAgentRunner.McpServer s : servers) {
            ObjectNode server = all.putObject(s.name());
            server.put("httpUrl", s.url());
            server.put("trust", true);
            if (s.token() != null) {
                server.putObject("headers").put("Authorization", "Bearer " + s.token());
            }
        }
        return root.toString();
    }

    private void writeSettings(Path configDir, AgentRunRequest request) throws IOException {
        Files.writeString(configDir.resolve("settings.json"),
                settingsJson(CodexCliAgentRunner.mcpServers(request)));
    }

    /** Per SuperAgent session under the workspace, so {@code --resume <uuid>} finds the same store. */
    static Path configDir(AgentRunRequest request) {
        return Path.of(request.workspaceDir(), ".superagent", "gemini-home", request.sessionId());
    }

    /** Env for a Gemini process: key, optional base URL, and isolated config/home. */
    static Map<String, String> environment(RunCredential credential, Path configDir) {
        Map<String, String> env = new HashMap<>();
        if (credential != null) {
            env.put("GEMINI_API_KEY", credential.apiKey());
            if (credential.baseUrl() != null && !credential.baseUrl().isBlank()) {
                env.put("GOOGLE_GEMINI_BASE_URL", credential.baseUrl().strip());
            }
        }
        env.put("GEMINI_CONFIG_DIR", configDir.toString());
        // Keep projects.json / session files out of the operator's real home.
        env.put("HOME", configDir.toString());
        return env;
    }

    @Override
    protected Map<String, String> environment(AgentRunRequest request) {
        return environment(request.credential(), configDir(request));
    }

    @Override
    protected LineParser newParser() {
        return new GeminiStreamParser();
    }
}
