package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ImageAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@link AgentRunner} for OpenAI Codex ({@code codex exec --json}).
 *
 * <p>Session continuity: unlike Claude Code, Codex can't be told which session id to use —
 * it mints its own thread id. The runner keeps SuperAgent's session id as the identity and
 * records the Codex thread id under the workspace ({@code .superagent/codex-sessions/<id>}),
 * so a later turn — even after a restart — resumes the same thread with
 * {@code codex exec resume <thread>}.
 *
 * <p>The prompt goes in on stdin ({@code -}): no argv flag injection, and stdin is closed so
 * Codex doesn't sit waiting for more input. Permissions map from the Claude-style settings:
 * skip-permissions → bypass sandbox; an allowlist with a write-capable tool → workspace-write;
 * otherwise read-only. Codex reports token usage but no dollar cost, so no USAGE is emitted.
 */
@Component
public class CodexCliAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(CodexCliAgentRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> WRITE_TOOLS = Set.of("Edit", "Write", "MultiEdit", "NotebookEdit", "Bash");
    /**
     * Codex thread ids are UUIDs. The id file sits in a workspace the agent can write to, so its
     * content is untrusted: anything else (e.g. "--dangerously-bypass-approvals-and-sandbox") would be
     * smuggled into argv on the next resume. Only an exact UUID is ever passed on.
     */
    private static final Pattern THREAD_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final String command;
    private final long timeoutSeconds;

    public CodexCliAgentRunner(
            @Value("${superagent.agent.codex.command:codex}") String command,
            @Value("${superagent.agent.codex.timeout-seconds:600}") long timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AgentType type() {
        return AgentType.CODEX;
    }

    @Override
    public void run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
        String threadId = request.isFirstTurn() ? null : readThreadId(request);
        if (!request.isFirstTurn() && threadId == null) {
            // Only possible if the first turn died before Codex reported a thread: there's no
            // conversation to resume, so start one under the same SuperAgent session id.
            log.warn("session {} has no recorded codex thread; starting a new one", request.sessionId());
        }
        List<String> cmd = buildCommand(request, threadId);
        log.info("session {} running: {} (cwd={})", request.sessionId(), String.join(" ", cmd), request.workspaceDir());

        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(request.workspaceDir()));
            // MCP bearer tokens go through the environment, never argv (visible in ps / logs).
            for (McpServer server : mcpServers(request)) {
                if (server.token() != null) {
                    pb.environment().put(server.tokenEnvVar(), server.token());
                }
            }
            process = pb.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(prompt(request, threadId == null).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            emit(sink, request, 0, AgentEventType.ERROR, "Failed to start codex: " + e.getMessage());
            return;
        }
        cancellation.onCancel(process::destroyForcibly);
        StringBuilder stderr = new StringBuilder();
        Thread drain = new Thread(() -> drainStderr(process.getErrorStream(), stderr), "codex-stderr-" + request.sessionId());
        drain.setDaemon(true);
        drain.start();

        CodexStreamParser parser = new CodexStreamParser();
        long seq = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                for (CodexStreamParser.ParsedEvent e : parser.parse(line)) {
                    if (e.type() == AgentEventType.SYSTEM && threadId == null) {
                        recordThreadId(request, e.payload());
                    }
                    emit(sink, request, seq++, e.type(), e.payload());
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                emit(sink, request, seq, AgentEventType.ERROR, "Codex timed out after " + timeoutSeconds + "s");
                return;
            }
            drain.join(2000);
            int code = process.exitValue();
            if (code != 0 && !parser.finished() && !cancellation.isCancelled()) {
                emit(sink, request, seq, AgentEventType.ERROR, "codex exited with code " + code + ": " + tail(stderr.toString()));
            }
        } catch (IOException e) {
            process.destroyForcibly();
            emit(sink, request, seq, AgentEventType.ERROR, "Codex run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            emit(sink, request, seq, AgentEventType.ERROR, "Codex run interrupted");
        }
    }

    List<String> buildCommand(AgentRunRequest request, String threadId) {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.add("exec");
        cmd.add("--json");
        cmd.add("--skip-git-repo-check"); // task workspaces aren't git repositories
        if (request.skipPermissions()) {
            cmd.add("--dangerously-bypass-approvals-and-sandbox");
        } else {
            cmd.add("-s");
            boolean canWrite = request.allowedTools() != null && request.allowedTools().stream().anyMatch(WRITE_TOOLS::contains);
            cmd.add(canWrite ? "workspace-write" : "read-only");
        }
        for (McpServer server : mcpServers(request)) {
            // Values are TOML; a JSON-quoted string is a valid TOML basic string.
            cmd.add("-c");
            cmd.add("mcp_servers." + server.name() + ".url=" + jsonString(server.url()));
            // exec mode can't prompt, so an unapproved MCP call is simply refused. These servers come from
            // SuperAgent's own run config and are already scoped per turn — the same trust as Claude's allowlist.
            cmd.add("-c");
            cmd.add("mcp_servers." + server.name() + ".default_tools_approval_mode=\"approve\"");
            if (server.token() != null) {
                cmd.add("-c");
                cmd.add("mcp_servers." + server.name() + ".bearer_token_env_var=" + jsonString(server.tokenEnvVar()));
            }
        }
        if (threadId != null) {
            cmd.add("resume");
        }
        if (request.model() != null && !request.model().isBlank()) {
            if (request.model().startsWith("-")) {
                throw new IllegalArgumentException("Invalid model name: " + request.model());
            }
            cmd.add("-m");
            cmd.add(request.model());
        }
        for (ImageAttachment image : request.images()) {
            // "=" form: -i/--image takes several values, so a bare "-i path" would also swallow the "-" stdin marker.
            cmd.add("--image=" + image.path());
        }
        if (threadId != null) {
            cmd.add(threadId);
        }
        cmd.add("-"); // prompt on stdin
        return cmd;
    }

    /** An HTTP MCP server from the request's Claude-style {@code mcpConfig} JSON. */
    record McpServer(String name, String url, String token) {
        String tokenEnvVar() {
            return "SUPERAGENT_MCP_TOKEN_" + name.toUpperCase(Locale.ROOT).replace('-', '_');
        }
    }

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * Codex has no --mcp-config: the same servers are passed as {@code -c mcp_servers.<name>.*}
     * overrides. Only HTTP servers with a bearer token (how SuperAgent's own MCP authenticates) are
     * translated; anything unparseable is skipped rather than failing the turn.
     */
    static List<McpServer> mcpServers(AgentRunRequest request) {
        if (request.mcpConfig() == null || request.mcpConfig().isBlank()) {
            return List.of();
        }
        List<McpServer> out = new ArrayList<>();
        try {
            JsonNode servers = JSON.readTree(request.mcpConfig()).path("mcpServers");
            servers.fieldNames().forEachRemaining(name -> {
                JsonNode s = servers.get(name);
                String url = s.path("url").asText("");
                if (!SERVER_NAME.matcher(name).matches() || url.isBlank()) {
                    return;
                }
                String auth = s.path("headers").path("Authorization").asText("");
                out.add(new McpServer(name, url, auth.startsWith("Bearer ") ? auth.substring(7).trim() : null));
            });
        } catch (IOException e) {
            log.warn("session {}: ignoring unreadable MCP config: {}", request.sessionId(), e.getMessage());
        }
        return out;
    }

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Codex has no system-prompt flag, so the persona (if any) leads every message: repeating it each
     * turn is what lets an edited character take effect in an existing thread, as it does for Claude.
     * The agent's memory joins it only when the thread is new ({@code freshThread}).
     */
    static String prompt(AgentRunRequest request, boolean freshThread) {
        String system = freshThread ? request.systemPromptWithMemory() : request.systemPrompt();
        if (system != null && !system.isBlank()) {
            return "<system>\n" + system.strip() + "\n</system>\n\n" + request.prompt();
        }
        return request.prompt();
    }

    private static Path threadFile(AgentRunRequest request) {
        return Path.of(request.workspaceDir(), ".superagent", "codex-sessions", request.sessionId());
    }

    private String readThreadId(AgentRunRequest request) {
        try {
            Path f = threadFile(request);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            String id = Files.readString(f, StandardCharsets.UTF_8).strip();
            if (!THREAD_ID.matcher(id).matches()) {
                log.warn("session {} has a malformed codex thread id file; ignoring it", request.sessionId());
                return null;
            }
            return id;
        } catch (IOException e) {
            log.warn("session {} could not read codex thread id: {}", request.sessionId(), e.getMessage());
            return null;
        }
    }

    private void recordThreadId(AgentRunRequest request, String threadId) {
        if (!THREAD_ID.matcher(threadId).matches()) {
            log.warn("session {} got an unexpected codex thread id; not recording it", request.sessionId());
            return;
        }
        try {
            Path f = threadFile(request);
            Files.createDirectories(f.getParent());
            Files.writeString(f, threadId, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("session {} could not record codex thread {}: {}", request.sessionId(), threadId, e.getMessage());
        }
    }

    private static void emit(AgentEventSink sink, AgentRunRequest request, long seq, AgentEventType type, String payload) {
        sink.emit(new AgentEvent(request.sessionId(), null, seq, type, payload, Instant.now()));
    }

    private static void drainStderr(InputStream in, StringBuilder into) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (into) {
                    into.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() <= 400 ? t : t.substring(t.length() - 400);
    }
}
