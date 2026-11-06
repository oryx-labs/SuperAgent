package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-turn credentials for SuperAgent's own {@code /mcp} endpoint. Every agent turn gets a fresh
 * token bound to the user it runs for, revoked when the turn ends; {@code McpController} resolves
 * the token back to its grant, which decides both the toolset and whose data it touches:
 * <ul>
 *   <li>{@link Kind#CHAT}: task, file and skill tools — tasks and files made in a chat belong to the
 *       chatting user, and each file remembers the reply ({@code messageId}) that produced it;</li>
 *   <li>{@link Kind#TASK}: file and skill tools — files belong to the task owner;</li>
 *   <li>{@link Kind#SUPER}: a SuperAgent round — team, plan and assignment tools for that user's team.</li>
 * </ul>
 * Either way, a file can only be saved out of that run's own workspace.
 * Tokens live in memory only: a run is spawned by this process, so it always holds a current one.
 */
@Component
public class McpGrants {

    public enum Kind { CHAT, TASK, SUPER }

    /**
     * What a token stands for. {@code taskId} is set for task grants; {@code agentId}, {@code sessionId} and
     * {@code messageId} (the reply being written) for chats with an agent.
     */
    public record Grant(String token, Kind kind, String owner, String taskId, String agentId, String sessionId,
                        String messageId, Path workspace) {
    }

    /** SuperAgent's inbox tools as the Claude CLI sees them (server "superagent"), for its round allowlist. */
    public static final List<String> INBOX_TOOLS = List.of(
            "mcp__superagent__inbox_search", "mcp__superagent__inbox_add_todo", "mcp__superagent__inbox_notify",
            "mcp__superagent__inbox_mark", "mcp__superagent__todo_list", "mcp__superagent__todo_update");

    /** File (work output) and skill tool names as the Claude CLI sees them, for chat and task allowlists. */
    public static final List<String> WORK_TOOLS = List.of(
            "mcp__superagent__file_write", "mcp__superagent__file_save", "mcp__superagent__file_list",
            "mcp__superagent__skill_list", "mcp__superagent__skill_get", "mcp__superagent__skill_create",
            "mcp__superagent__skill_update", "mcp__superagent__skill_add_image");

    /**
     * Told to every chat and task run that has the file tools: work results go to the user through them.
     * Without it a model writes the result into its workspace (or only into the reply) and the user never gets it.
     */
    public static final String FILES_HINT = "【交付规则】用户要的成果是一份东西（文章、讲义、报告、方案、文案、代码文件、图片、视频、PPT 等）时，"
            + "必须用 mcp__superagent__file_write（文本内容）或 mcp__superagent__file_save（工作区里生成的文件）交付，"
            + "用户会在「文件」里看到它，对话里也会出现文件卡片。不要用内置的 Write 工具写成交付物，也不要只把全文贴在回复里——那样用户拿不到文件。"
            + "修改之前交付过的文件：先用 file_list 找到它，再带 replace_id 覆盖，不要另存一份。交付后回复里简要说明内容并提文件名，不要贴路径。"
            + "交付时带上 tags（1~3 个内容主题标签，优先复用 file_list 里已在用的标签），方便用户按主题找文件。"
            + "只是闲聊、回答问题、给简短建议时直接回复即可，不必建文件。";

    /** Whether agents can reach {@link #selfUrl()}: unchecked until startup's self-check, then ok or error. */
    public record SelfStatus(String state, String error, Instant checkedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(McpGrants.class);

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    /** Set explicitly (a proxy, another host); otherwise found from the port the server actually listens on. */
    private final boolean configured;
    private volatile String selfUrl;
    private volatile SelfStatus status = new SelfStatus("unchecked", null, null);

    public McpGrants(String selfUrl) {
        this.configured = true;
        this.selfUrl = selfUrl == null ? "" : selfUrl.trim();
    }

    @Autowired
    public McpGrants(@Value("${superagent.mcp.self-url:}") String selfUrl, @Value("${server.port:18080}") int port) {
        this.configured = selfUrl != null && !selfUrl.isBlank();
        this.selfUrl = configured ? selfUrl.trim() : local(port);
    }

    private static String local(int port) {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    /** Agents always get SuperAgent's own MCP; there is no way to turn it off. */
    public boolean available() {
        return !selfUrl.isEmpty();
    }

    public String selfUrl() {
        return selfUrl;
    }

    public SelfStatus selfStatus() {
        return status;
    }

    /** The port the server really listens on (it may differ from server.port, e.g. a command-line override). */
    @EventListener
    public void onWebServer(WebServerInitializedEvent event) {
        if (!configured && event.getWebServer().getPort() > 0) {
            selfUrl = local(event.getWebServer().getPort());
        }
    }

    /**
     * Once the app is up, calls its own MCP the way an agent would (a real grant, a ping), so a wrong URL shows
     * as an error at startup and on the Connectors page instead of agents quietly missing their tools.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void selfCheck() {
        Thread t = new Thread(() -> {
            Grant probe = open(Kind.CHAT, "__selfcheck__", null, null, null, null, null);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(selfUrl)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + probe.token())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                        .build();
                HttpResponse<String> resp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                        .send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("\"result\"")) {
                    status = new SelfStatus("ok", null, Instant.now());
                    log.info("SuperAgent MCP reachable at {}", selfUrl);
                } else {
                    fail("HTTP " + resp.statusCode() + " — " + selfUrl + " 不是本服务的 MCP（请检查 superagent.mcp.self-url）");
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                fail("连不上 " + selfUrl + "（" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        + "），请检查 superagent.mcp.self-url");
            } finally {
                close(probe);
            }
        }, "mcp-selfcheck");
        t.setDaemon(true);
        t.start();
    }

    private void fail(String error) {
        status = new SelfStatus("error", error, Instant.now());
        log.error("SuperAgent MCP self-check failed: {} — agents won't get tasks, files, skills or team tools", error);
    }

    public Grant openChat(String owner, String agentId, String sessionId, String messageId, String workspaceDir) {
        return open(Kind.CHAT, owner, null, agentId, sessionId, messageId, workspaceDir == null ? null : Path.of(workspaceDir));
    }

    /** A SuperAgent round for {@code owner}; {@code agentId} is that user's SuperAgent. */
    public Grant openSuper(String owner, String agentId, String workspaceDir) {
        return openSuper(owner, agentId, null, null, workspaceDir);
    }

    /** A SuperAgent chat turn: its session and reply, so what it proposes (an invitation) shows under that reply. */
    public Grant openSuper(String owner, String agentId, String sessionId, String messageId, String workspaceDir) {
        return open(Kind.SUPER, owner, null, agentId, sessionId, messageId, workspaceDir == null ? null : Path.of(workspaceDir));
    }

    public Grant openTask(String owner, String taskId, String workspaceDir) {
        return open(Kind.TASK, owner, taskId, null, null, null, Path.of(workspaceDir));
    }

    private Grant open(Kind kind, String owner, String taskId, String agentId, String sessionId, String messageId,
                       Path workspace) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        Grant grant = new Grant(HexFormat.of().formatHex(bytes), kind, owner, taskId, agentId, sessionId, messageId, workspace);
        grants.put(grant.token(), grant);
        return grant;
    }

    public void close(Grant grant) {
        if (grant != null) {
            grants.remove(grant.token());
        }
    }

    public Optional<Grant> find(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(grants.get(token));
    }

    /** Claude-style {@code --mcp-config} JSON; the Codex runner translates it to {@code -c mcp_servers.*}. */
    public String mcpConfig(Grant grant) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject("superagent");
        server.put("type", "http");
        server.put("url", selfUrl);
        server.putObject("headers").put("Authorization", "Bearer " + grant.token());
        return root.toString();
    }

    /**
     * {@code baseConfig} (SuperAgent's own server, or null) plus the company's connectors, in the same
     * {@code --mcp-config} shape. Stdio servers carry {@code command}/{@code args}/{@code env}; runners that only
     * speak HTTP MCP skip them. Null when there is nothing to configure.
     */
    public static String withServers(String baseConfig, List<McpServerSpec> servers) {
        if (servers == null || servers.isEmpty()) {
            return baseConfig;
        }
        ObjectMapper json = new ObjectMapper();
        ObjectNode root;
        try {
            JsonNode parsed = baseConfig == null ? null : json.readTree(baseConfig);
            root = parsed instanceof ObjectNode o ? o : json.createObjectNode();
        } catch (IOException e) {
            root = json.createObjectNode();
        }
        ObjectNode all = root.has("mcpServers") ? (ObjectNode) root.get("mcpServers") : root.putObject("mcpServers");
        for (McpServerSpec s : servers) {
            if (all.has(s.name())) {
                continue;
            }
            ObjectNode server = all.putObject(s.name());
            if (s.transport() == ConnectorTransport.STDIO) {
                server.put("type", "stdio");
                server.put("command", s.command());
                s.args().forEach(server.putArray("args")::add);
                ObjectNode env = server.putObject("env");
                s.env().forEach(env::put);
            } else {
                server.put("type", s.transport() == ConnectorTransport.SSE ? "sse" : "http");
                server.put("url", s.url());
                ObjectNode headers = server.putObject("headers");
                s.headers().forEach(headers::put);
            }
        }
        return root.toString();
    }

    /**
     * A file inside a task grant's workspace, given relative to it (or absolute within it). Compared by
     * real path, so a symlink in the workspace can't hand out a file from elsewhere on the host.
     */
    public Path workspaceFile(Grant grant, String file) {
        if (grant.workspace() == null) {
            throw new IllegalArgumentException("This run has no workspace");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        try {
            Path workspace = grant.workspace().toRealPath();
            Path requested = Path.of(file.strip());
            Path candidate = requested.isAbsolute() ? requested : workspace.resolve(requested);
            if (!Files.exists(candidate)) {
                throw new IllegalArgumentException("No such file in the task workspace: " + file);
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(workspace) || !Files.isRegularFile(real)) {
                throw new IllegalArgumentException("Not a file in the task workspace: " + file);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage());
        }
    }
}
