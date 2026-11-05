package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
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
 *   <li>{@link Kind#CHAT}: task tools plus knowledge-base tools — tasks created from chat and notes
 *       saved from chat belong to the chatting user;</li>
 *   <li>{@link Kind#TASK}: knowledge-base tools only — writes go to the task owner's knowledge base;</li>
 *   <li>{@link Kind#SUPER}: a SuperAgent round — team, plan and assignment tools for that user's team.</li>
 * </ul>
 * Either way, files can only be copied into the knowledge base out of that run's own workspace.
 * Tokens live in memory only: a run is spawned by this process, so it always holds a current one.
 */
@Component
public class McpGrants {

    public enum Kind { CHAT, TASK, SUPER }

    /** What a token stands for. {@code taskId} is set for task grants, {@code agentId} for chats with an agent. */
    public record Grant(String token, Kind kind, String owner, String taskId, String agentId, Path workspace) {
    }

    /** SuperAgent's inbox tools as the Claude CLI sees them (server "superagent"), for its round allowlist. */
    public static final List<String> INBOX_TOOLS = List.of(
            "mcp__superagent__inbox_search", "mcp__superagent__inbox_add_todo", "mcp__superagent__inbox_notify",
            "mcp__superagent__inbox_mark", "mcp__superagent__todo_list", "mcp__superagent__todo_update");

    /** Knowledge-base and skill tool names as the Claude CLI sees them, for chat and task allowlists. */
    public static final List<String> KNOWLEDGE_TOOLS = List.of(
            "mcp__superagent__knowledge_list_folders", "mcp__superagent__knowledge_create_folder",
            "mcp__superagent__knowledge_write", "mcp__superagent__knowledge_save",
            "mcp__superagent__skill_list", "mcp__superagent__skill_get", "mcp__superagent__skill_create",
            "mcp__superagent__skill_update", "mcp__superagent__skill_add_image");

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String selfUrl;

    public McpGrants(@Value("${superagent.mcp.self-url:http://127.0.0.1:8080/mcp}") String selfUrl) {
        this.selfUrl = selfUrl == null ? "" : selfUrl.trim();
    }

    /** False when no self URL is configured, i.e. agents can't reach this server's MCP at all. */
    public boolean available() {
        return !selfUrl.isEmpty();
    }

    public Grant openChat(String owner, String agentId, String workspaceDir) {
        return open(Kind.CHAT, owner, null, agentId, workspaceDir == null ? null : Path.of(workspaceDir));
    }

    /** A SuperAgent round for {@code owner}; {@code agentId} is that user's SuperAgent. */
    public Grant openSuper(String owner, String agentId, String workspaceDir) {
        return open(Kind.SUPER, owner, null, agentId, workspaceDir == null ? null : Path.of(workspaceDir));
    }

    public Grant openTask(String owner, String taskId, String workspaceDir) {
        return open(Kind.TASK, owner, taskId, null, Path.of(workspaceDir));
    }

    private Grant open(Kind kind, String owner, String taskId, String agentId, Path workspace) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        Grant grant = new Grant(HexFormat.of().formatHex(bytes), kind, owner, taskId, agentId, workspace);
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
