package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses {@code codex exec --json} JSONL into normalised events. Stateful per turn: it
 * remembers the thread id (Codex's own session id, needed to resume) and the last agent
 * message, which becomes the turn's RESULT when {@code turn.completed} arrives.
 *
 * <p>Wire format (verified against codex-cli 0.154):
 * <ul>
 *   <li>{@code thread.started {thread_id}} → SYSTEM</li>
 *   <li>{@code item.started|item.completed {item:{type,...}}}: agent_message → TEXT,
 *       reasoning → THINKING, command_execution / file_change / mcp_tool_call / web_search
 *       → TOOL_USE (once per item id)</li>
 *   <li>{@code turn.completed} → RESULT; {@code turn.failed} / top-level {@code error} → ERROR</li>
 * </ul>
 * {@code item.type=error} items are configuration warnings (e.g. "ignoring 1 unrecognized
 * configuration setting") rather than failures, so they are logged, not surfaced.
 */
public class CodexStreamParser {

    private static final Logger log = LoggerFactory.getLogger(CodexStreamParser.class);
    private static final Set<String> TOOL_ITEMS = Set.of("command_execution", "file_change", "mcp_tool_call", "web_search");

    public record ParsedEvent(AgentEventType type, String payload) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> announcedTools = new HashSet<>();
    private String threadId;
    private String lastMessage;
    private boolean finished;

    public List<ParsedEvent> parse(String line) {
        JsonNode e;
        try {
            e = mapper.readTree(line);
        } catch (Exception ex) {
            log.debug("Skipping unparseable codex line: {}", ex.getMessage());
            return List.of();
        }
        List<ParsedEvent> out = new ArrayList<>();
        switch (e.path("type").asText("")) {
            case "thread.started" -> {
                threadId = e.path("thread_id").asText(null);
                if (threadId != null) {
                    out.add(new ParsedEvent(AgentEventType.SYSTEM, threadId));
                }
            }
            case "item.started" -> announceTool(e.path("item"), out);
            case "item.completed" -> {
                JsonNode item = e.path("item");
                switch (item.path("type").asText("")) {
                    case "agent_message" -> {
                        lastMessage = item.path("text").asText("");
                        out.add(new ParsedEvent(AgentEventType.TEXT, lastMessage));
                    }
                    case "reasoning" -> {
                        String text = item.path("text").asText("");
                        if (!text.isBlank()) {
                            out.add(new ParsedEvent(AgentEventType.THINKING, text));
                        }
                    }
                    case "error" -> log.debug("codex warning: {}", item.path("message").asText(""));
                    default -> announceTool(item, out);
                }
            }
            case "turn.completed" -> {
                finished = true;
                out.add(new ParsedEvent(AgentEventType.RESULT, lastMessage == null ? "" : lastMessage));
            }
            case "turn.failed" -> {
                finished = true;
                out.add(new ParsedEvent(AgentEventType.ERROR, e.path("error").path("message").asText("Codex turn failed")));
            }
            case "error" -> out.add(new ParsedEvent(AgentEventType.ERROR, e.path("message").asText("Codex error")));
            default -> { /* turn.started, item.updated, ...: nothing to surface */ }
        }
        return out;
    }

    /** Codex's own session id for this turn, once {@code thread.started} has been seen. */
    public String threadId() {
        return threadId;
    }

    /** True once the turn reported completion or failure. */
    public boolean finished() {
        return finished;
    }

    private void announceTool(JsonNode item, List<ParsedEvent> out) {
        String type = item.path("type").asText("");
        if (!TOOL_ITEMS.contains(type) || !announcedTools.add(item.path("id").asText(type + announcedTools.size()))) {
            return;
        }
        out.add(new ParsedEvent(AgentEventType.TOOL_USE, toolLabel(type, item)));
    }

    private static String toolLabel(String type, JsonNode item) {
        String detail = switch (type) {
            case "command_execution" -> item.path("command").asText("");
            case "file_change" -> {
                List<String> paths = new ArrayList<>();
                item.path("changes").forEach(c -> paths.add(c.path("path").asText("")));
                yield String.join(", ", paths);
            }
            case "mcp_tool_call" -> item.path("server").asText("") + "." + item.path("tool").asText("");
            case "web_search" -> item.path("query").asText("");
            default -> "";
        };
        String name = switch (type) {
            case "command_execution" -> "Bash";
            case "file_change" -> "Edit";
            case "mcp_tool_call" -> "MCP";
            default -> "WebSearch";
        };
        detail = detail.strip();
        if (detail.length() > 120) {
            detail = detail.substring(0, 117) + "...";
        }
        return detail.isEmpty() || ".".equals(detail) ? name : name + ": " + detail;
    }
}
