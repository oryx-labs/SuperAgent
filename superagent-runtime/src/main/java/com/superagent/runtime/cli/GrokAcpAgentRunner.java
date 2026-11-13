package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.runtime.acp.AcpClient;
import com.superagent.runtime.acp.AcpEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AgentRunner} for Grok Build (xAI). Grok has no command-line MCP configuration, so
 * it cannot be driven the way the other CLIs are: instead it speaks the
 * <a href="https://agentclientprotocol.com">Agent Client Protocol</a> over stdio
 * ({@code grok agent stdio}), and MCP servers are injected into the session itself.
 *
 * <p>That injection is what makes this runner possible. SuperAgent issues a short-lived
 * bearer token per turn and revokes it when the turn ends; passing the server with the
 * session keeps the token off disk and out of argv, and re-injecting it on
 * {@code session/load} hands the resumed session the current turn's token.
 *
 * <p>The agent runs in its own home under the workspace
 * ({@code .superagent/grok-home/<sessionId>}), so the machine's own
 * {@code ~/.grok/config.toml}, {@code ~/.cursor/mcp.json} and {@code ~/.claude.json} stay
 * invisible — and so {@code session/load} can find the previous turn's session file.
 */
@Component
public class GrokAcpAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(GrokAcpAgentRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String command;
    private final long timeoutSeconds;
    private CliPaths cliPaths; // null in tests: the command runs from the process PATH as given

    public GrokAcpAgentRunner(
            @Value("${superagent.agent.grok.command:grok}") String command,
            @Value("${superagent.agent.grok.timeout-seconds:600}") long timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Run the CLI from the shared search path, so one the Models page installed is found. */
    @Autowired(required = false)
    void setCliPaths(CliPaths cliPaths) {
        this.cliPaths = cliPaths;
    }

    @Override
    public AgentType type() {
        return AgentType.GROK;
    }

    @Override
    public void run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation) {
        AtomicLong seq = new AtomicLong();
        if (request.credential() == null) {
            emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR,
                    "还没有配置 grok 的 API Key：请到 Models 页的 API Key 里添加");
            return;
        }
        AcpClient client = null;
        try {
            Path home = grokHome(request);
            Files.createDirectories(home);
            Process process = start(request, home);
            client = new AcpClient(process, Duration.ofSeconds(timeoutSeconds));
            AcpClient toClose = client;
            cancellation.onCancel(() -> {
                process.destroyForcibly();
                toClose.close();
            });
            turn(request, sink, seq, client);
        } catch (IOException e) {
            emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR,
                    "Failed to start grok: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("session {}: grok turn failed", request.sessionId(), e);
            emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR, "grok run failed: " + e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /** Starts {@code grok agent stdio} in the run's workspace, with its own home. */
    private Process start(AgentRunRequest request, Path home) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.add("agent");
        // Same rule as Claude's --dangerously-skip-permissions: only when the run opted in.
        // Otherwise Grok asks via session/request_permission and we answer from the allowlist.
        if (request.skipPermissions()) {
            log.warn("session {} starting grok with --always-approve: the agent may run any tool "
                            + "without approval. Enable superagent.agent.skip-permissions only in an isolated sandbox.",
                    request.sessionId());
            cmd.add("--always-approve");
        }
        if (request.model() != null && !request.model().isBlank()) {
            cmd.add("--model");
            cmd.add(request.model());
        }
        cmd.add("stdio");

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(request.workspaceDir()));
        pb.redirectErrorStream(false);
        if (cliPaths != null) {
            cliPaths.apply(pb);
        }
        Map<String, String> env = pb.environment();
        // Its own home: the machine's own Grok config, MCP servers and skills stay invisible.
        // Kept under the workspace so session/load finds the previous turn's session file.
        env.put("HOME", home.toString());
        env.put("GROK_HOME", home.resolve(".grok").toString());
        applyCredential(env, request);
        log.info("session {} running: grok agent stdio (cwd={})", request.sessionId(), request.workspaceDir());
        return pb.start();
    }

    /**
     * Points the run at the pool entry's endpoint and key. Grok resolves credentials as
     * per-model config, then a session token, then {@code XAI_API_KEY}; a stored session token
     * would win over the key SuperAgent chose, so the run's home has none.
     *
     * <p>The endpoint follows the shape of the key's base URL: xAI's own API speaks the
     * Responses protocol, an OpenAI-compatible relay speaks chat completions.
     */
    private void applyCredential(Map<String, String> env, AgentRunRequest request) {
        var credential = request.credential();
        env.put("XAI_API_KEY", credential.apiKey());
        if (credential.baseUrl() == null) {
            return;
        }
        String base = credential.baseUrl();
        if (base.contains("/chat/completions") || base.endsWith("/compatibility")) {
            env.put("GROK_MODELS_BASE_URL", base);
        } else {
            env.put("GROK_XAI_API_BASE_URL", base);
        }
    }

    /** One turn: a session (new or loaded), the prompt, and the updates it produces. */
    private void turn(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, AcpClient client) {
        client.request("initialize", initializeParams()).orTimeout(30, TimeUnit.SECONDS).join();
        client.notify("notifications/initialized", JSON.createObjectNode());
        String sessionId = openSession(request, sink, seq, client);
        if (sessionId == null) {
            return;
        }
        emit(sink, request, seq.getAndIncrement(), AgentEventType.SYSTEM, sessionId);
        prompt(request, sink, seq, client, sessionId);
    }

    /** Creates the session, or loads the one this conversation already has. */
    private String openSession(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, AcpClient client) {
        List<AcpClient.McpServer> servers = mcpServers(request);
        ObjectNode params = AcpClient.mcpServers(servers);
        params.put("cwd", request.workspaceDir());
        boolean loading = !request.isFirstTurn() && request.agentSessionId() != null;
        if (loading) {
            params.put("sessionId", request.agentSessionId());
        }
        // Rules / persona / "always approve" travel as session _meta — grok agent has no --rules flag.
        ObjectNode meta = params.putObject("_meta");
        if (request.skipPermissions()) {
            meta.put("yoloMode", true);
            meta.put("autoMode", true);
        }
        String system = request.systemPromptWithMemory();
        if (system != null && !system.isBlank()) {
            meta.put("rules", system);
        }
        String method = loading ? "session/load" : "session/new";
        try {
            JsonNode message = client.request(method, params)
                    .orTimeout(60, TimeUnit.SECONDS)
                    .thenApply(AcpClient.Message::json)
                    .join();
            if (message.has("error")) {
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR,
                        "grok " + method + " failed: " + rpcError(message));
                return null;
            }
            JsonNode result = message.path("result");
            if (result.isMissingNode()) {
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR,
                        "grok could not " + (loading ? "load" : "start") + " a session");
                return null;
            }
            String sessionId = result.path("sessionId").asText(loading ? request.agentSessionId() : "");
            if (sessionId.isBlank()) {
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR, "grok returned no session id");
                return null;
            }
            if (loading) {
                // session/load replays prior turns as session/update; drain them so they
                // never mix into this turn's RESULT / usage.
                discardLoadReplay(request, sink, seq, client);
            }
            return sessionId;
        } catch (RuntimeException e) {
            log.warn("session {}: grok {} failed: {}", request.sessionId(), method, e.getMessage());
            emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR,
                    "grok " + method + " failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Drops historical {@code session/update} traffic from {@code session/load}. Agent-side
     * requests that arrive in the same window are still answered so the load cannot hang.
     */
    private void discardLoadReplay(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, AcpClient client) {
        try {
            AcpClient.Message message;
            while ((message = client.take(Duration.ofMillis(200))) != null) {
                String method = message.method();
                if ("session/request_permission".equals(method)) {
                    answerPermission(request, sink, seq, client, message);
                } else if ("fs/read_text_file".equals(method)) {
                    answerRead(request, client, message);
                } else if ("fs/write_text_file".equals(method)) {
                    answerWrite(request, client, message);
                }
                // session/update and _x.ai/* replay notifications: discard
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends the prompt and streams what comes back. The agent's updates arrive as
     * notifications while the prompt's own answer arrives as the request's response; both
     * are handled, and the permission requests the agent makes in between are answered
     * from the run's tool allowlist. ChatService / TaskService conclude the turn on
     * {@link AgentEventType#RESULT}, so the accumulated assistant text is emitted once at the end.
     */
    private void prompt(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, AcpClient client, String sessionId) {
        StringBuilder text = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean usageReported = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<AcpClient.Message> answer = client.request("session/prompt",
                AcpEventMapper.prompt(sessionId, request.prompt()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline && !answer.isDone()) {
            AcpClient.Message message;
            try {
                message = client.take(Duration.ofSeconds(1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR, "grok turn interrupted");
                return;
            }
            if (message != null) {
                handle(request, sink, seq, client, message, text, usageReported);
            }
        }
        // Drain anything that raced the prompt response.
        try {
            AcpClient.Message late;
            while ((late = client.take(Duration.ofMillis(50))) != null) {
                handle(request, sink, seq, client, late, text, usageReported);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            JsonNode message = answer.orTimeout(Math.max(1, (deadline - System.nanoTime()) / 1_000_000),
                    TimeUnit.MILLISECONDS).join().json();
            if (message.has("error")) {
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR,
                        "grok turn failed: " + rpcError(message));
                return;
            }
            JsonNode result = message.path("result");
            String stopReason = result.path("stopReason").asText("");
            if ("cancelled".equals(stopReason)) {
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR, "grok stopped the turn: cancelled");
                return;
            }
            if (!"end_turn".equals(stopReason) && !stopReason.isBlank()) {
                emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR, "grok stopped the turn: " + stopReason);
                return;
            }
            JsonNode meta = result.path("_meta");
            if (meta.isObject()) {
                ObjectNode usageParams = JSON.createObjectNode();
                usageParams.set("usage", meta.path("usage").isObject() ? meta.get("usage") : meta);
                reportUsage(request, sink, seq, usageParams, usageReported);
            }
            emit(sink, request, seq.getAndIncrement(), AgentEventType.RESULT, text.toString());
        } catch (RuntimeException e) {
            emit(sink, request, seq.getAndIncrement(), AgentEventType.ERROR, "grok turn failed: " + e.getMessage());
        }
    }

    /** Handles one message from the agent: an update, a permission request, or a side notification. */
    private void handle(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, AcpClient client,
                        AcpClient.Message message, StringBuilder text,
                        java.util.concurrent.atomic.AtomicBoolean usageReported) {
        String method = message.method();
        if ("session/update".equals(method)) {
            JsonNode params = message.params();
            for (AcpEventMapper.Mapped mapped : AcpEventMapper.mapUpdate(params)) {
                if (mapped.type() == AgentEventType.TEXT) {
                    text.append(mapped.payload());
                }
                emit(sink, request, seq.getAndIncrement(), mapped.type(), mapped.payload());
            }
            if (AcpEventMapper.isUsageUpdate(params)) {
                reportUsage(request, sink, seq, params, usageReported);
            }
            return;
        }
        if ("session/request_permission".equals(method)) {
            answerPermission(request, sink, seq, client, message);
            return;
        }
        if ("fs/read_text_file".equals(method)) {
            answerRead(request, client, message);
            return;
        }
        if ("fs/write_text_file".equals(method)) {
            answerWrite(request, client, message);
            return;
        }
        if ("_x.ai/session_notification".equals(method)) {
            JsonNode params = message.params();
            if (AcpEventMapper.isUsageUpdate(params)) {
                reportUsage(request, sink, seq, params, usageReported);
            }
        }
    }

    /**
     * Serves {@code fs/read_text_file}: we advertised the capability on initialize, so the agent
     * will ask — and hang forever if we don't answer. Honours the run allowlist (Read/Edit/Write)
     * and stays inside the workspace (symlink-safe); Grok's own home under {@code .superagent}
     * is off-limits so a turn can't read another session's keys.
     */
    private void answerRead(AgentRunRequest request, AcpClient client, AcpClient.Message message) {
        if (!fsAllowed(request, false)) {
            client.respondError(message.id(), -32602, "read not permitted by this run's tool allowlist");
            return;
        }
        String path = message.params().path("path").asText("");
        Path file = resolveInWorkspace(request, path, true);
        if (file == null) {
            client.respondError(message.id(), -32602, "path outside workspace: " + path);
            return;
        }
        try {
            String content = Files.readString(file);
            Integer line = message.params().path("line").isNumber() ? message.params().path("line").asInt() : null;
            Integer limit = message.params().path("limit").isNumber() ? message.params().path("limit").asInt() : null;
            if (line != null || limit != null) {
                content = sliceLines(content, line == null ? 1 : line, limit);
            }
            client.respond(message.id(), JSON.createObjectNode().put("content", content));
        } catch (IOException e) {
            client.respondError(message.id(), -32000, "could not read: " + e.getMessage());
        }
    }

    /** Serves {@code fs/write_text_file}; ACP's success result is JSON null. */
    private void answerWrite(AgentRunRequest request, AcpClient client, AcpClient.Message message) {
        if (!fsAllowed(request, true)) {
            client.respondError(message.id(), -32602, "write not permitted by this run's tool allowlist");
            return;
        }
        String path = message.params().path("path").asText("");
        Path file = resolveInWorkspace(request, path, false);
        if (file == null) {
            client.respondError(message.id(), -32602, "path outside workspace: " + path);
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, message.params().path("content").asText(""));
            client.respond(message.id(), null);
        } catch (IOException e) {
            client.respondError(message.id(), -32000, "could not write: " + e.getMessage());
        }
    }

    /** Whether ACP filesystem access is allowed for this run (Claude-compatible tool names). */
    private boolean fsAllowed(AgentRunRequest request, boolean write) {
        if (request.skipPermissions()) {
            return true;
        }
        if (write) {
            return permitted(request, "Edit") || permitted(request, "Write");
        }
        return permitted(request, "Read") || permitted(request, "Edit") || permitted(request, "Write");
    }

    /**
     * Absolute path under the workspace, or null when the agent tried to leave it / touch Grok's
     * home. Compared by real path when the file exists, so a workspace symlink can't escape.
     */
    static Path resolveInWorkspace(AgentRunRequest request, String path, boolean mustExist) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path workspace = Path.of(request.workspaceDir()).toAbsolutePath().normalize();
            if (Files.exists(workspace)) {
                workspace = workspace.toRealPath();
            }
            Path grokHomeRoot = workspace.resolve(".superagent").resolve("grok-home").normalize();
            Path candidate = Path.of(path).isAbsolute()
                    ? Path.of(path).normalize()
                    : workspace.resolve(path).normalize();
            if (mustExist) {
                if (!Files.isRegularFile(candidate)) {
                    return null;
                }
                Path real = candidate.toRealPath();
                if (!real.startsWith(workspace) || real.startsWith(grokHomeRoot)) {
                    return null;
                }
                return real;
            }
            if (candidate.startsWith(grokHomeRoot)) {
                return null;
            }
            if (Files.exists(candidate)) {
                Path real = candidate.toRealPath();
                if (!real.startsWith(workspace) || real.startsWith(grokHomeRoot)) {
                    return null;
                }
                return real;
            }
            // New file: every existing ancestor must stay inside the workspace.
            Path probe = candidate.getParent();
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) {
                return null;
            }
            Path realParent = probe.toRealPath();
            if (!realParent.startsWith(workspace) || realParent.startsWith(grokHomeRoot)) {
                return null;
            }
            if (!candidate.startsWith(workspace)) {
                return null;
            }
            return candidate;
        } catch (IOException e) {
            return null;
        }
    }

    private static String sliceLines(String content, int startLine, Integer limit) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, startLine - 1);
        if (from >= lines.length) {
            return "";
        }
        int to = limit == null ? lines.length : Math.min(lines.length, from + Math.max(0, limit));
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, to));
    }

    /** Reports the turn's tokens and cost once — Grok may emit both turn_completed and prompt _meta. */
    private void reportUsage(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, JsonNode params,
                             java.util.concurrent.atomic.AtomicBoolean once) {
        List<com.superagent.core.usage.TokenUsage> tokens = AcpEventMapper.mapUsage(params, modelOf(request));
        if (tokens.isEmpty() || !once.compareAndSet(false, true)) {
            return;
        }
        Tokens.emit(sink, request.sessionId(), seq.getAndIncrement(), tokens);
        Double cost = AcpEventMapper.mapCost(params);
        if (cost != null) {
            emit(sink, request, seq.getAndIncrement(), AgentEventType.USAGE, String.valueOf(cost));
        }
    }

    /**
     * Answers the agent's permission request from the run's tool allowlist: a tool the run may
     * use is allowed, anything else is refused. This is a JSON-RPC <em>response</em> to the
     * agent's request id — a notification is not accepted.
     */
    private void answerPermission(AgentRunRequest request, AgentEventSink sink, AtomicLong seq, AcpClient client,
                                  AcpClient.Message message) {
        AcpEventMapper.PermissionRequest permission = AcpEventMapper.mapPermission(message.params());
        boolean allowed = request.skipPermissions()
                || AcpEventMapper.allowlistMatches(request.allowedTools(), permission);
        String option = AcpEventMapper.optionFor(permission, allowed);
        ObjectNode result = JSON.createObjectNode();
        result.set("outcome", JSON.createObjectNode()
                .put("outcome", "selected")
                .put("optionId", option == null ? AcpClient.PermissionDecision.REJECT_ONCE : option));
        client.respond(message.id(), result);
        if (!allowed) {
            String label = permission.label();
            if (label.isBlank()) {
                label = AcpEventMapper.permissionTool(permission);
            }
            emit(sink, request, seq.getAndIncrement(), AgentEventType.TOOL_RESULT,
                    "Permission denied: " + label);
        }
    }

    /** Whether the run's allowlist covers a built-in Claude tool name (used by ACP fs handlers). */
    private boolean permitted(AgentRunRequest request, String tool) {
        return AcpEventMapper.allowlistMatches(request.allowedTools(),
                new AcpEventMapper.PermissionRequest("", tool, tool, "", null, List.of()));
    }

    private static String rpcError(JsonNode message) {
        JsonNode error = message.path("error");
        String text = error.path("message").asText("");
        if (!text.isBlank()) {
            return text;
        }
        return error.isMissingNode() ? "JSON-RPC error" : error.toString();
    }

    /** The MCP servers of this turn, in the shape ACP expects. */
    static List<AcpClient.McpServer> mcpServers(AgentRunRequest request) {
        List<AcpClient.McpServer> out = new ArrayList<>();
        if (request.mcpConfig() == null || request.mcpConfig().isBlank()) {
            return out;
        }
        try {
            JsonNode servers = JSON.readTree(request.mcpConfig()).path("mcpServers");
            servers.fields().forEachRemaining(entry -> {
                JsonNode server = entry.getValue();
                String type = server.path("type").asText("");
                String name = entry.getKey();
                if ("http".equals(type) || "sse".equals(type)) {
                    String url = server.path("url").asText("");
                    if (url.isBlank()) {
                        return;
                    }
                    Map<String, String> headers = new java.util.LinkedHashMap<>();
                    server.path("headers").fields().forEachRemaining(h -> headers.put(h.getKey(), h.getValue().asText("")));
                    out.add(AcpClient.McpServer.http(name, url, headers));
                } else if ("stdio".equals(type)) {
                    List<String> args = new ArrayList<>();
                    server.path("args").forEach(a -> args.add(a.asText()));
                    Map<String, String> env = new java.util.LinkedHashMap<>();
                    server.path("env").fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText("")));
                    out.add(AcpClient.McpServer.stdio(name, server.path("command").asText(""), args, env));
                }
            });
        } catch (IOException e) {
            log.warn("session {}: ignoring unreadable MCP config: {}", request.sessionId(), e.getMessage());
        }
        return out;
    }

    /** Persistent per-conversation home so {@code session/load} finds the previous turn. */
    static Path grokHome(AgentRunRequest request) {
        return Path.of(request.workspaceDir(), ".superagent", "grok-home", request.sessionId());
    }

    /** The client's capabilities: SuperAgent lets the agent read and write the workspace itself. */
    private ObjectNode initializeParams() {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", AcpClient.PROTOCOL_VERSION);
        ObjectNode capabilities = params.putObject("clientCapabilities");
        capabilities.putObject("fs").put("readTextFile", true).put("writeTextFile", true);
        capabilities.put("terminal", false);
        params.putObject("clientInfo").put("name", "superagent").put("version", "1.0");
        return params;
    }

    private static String modelOf(AgentRunRequest request) {
        return request.model() == null || request.model().isBlank() ? "default" : request.model();
    }

    private static void emit(AgentEventSink sink, AgentRunRequest request, long seq, AgentEventType type, String payload) {
        sink.emit(new AgentEvent(request.sessionId(), null, seq, type, payload, Instant.now()));
    }
}
