package com.superagent.worker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses one line of Claude Code's {@code --output-format stream-json} output
 * into a normalised {@link ParsedEvent}, or empty when the line carries nothing
 * SuperAgent surfaces (hook chatter, status pings, bookkeeping frames).
 *
 * <p>The mapping follows the real wire format of the installed CLI:
 * <ul>
 *   <li>{@code {"type":"system","subtype":"init","session_id":...}} → SYSTEM (session id)</li>
 *   <li>{@code {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":...}}}} → TEXT</li>
 *   <li>…{@code "delta":{"type":"thinking_delta","thinking":...}} → THINKING</li>
 *   <li>{@code "content_block_start"} with {@code content_block.type=="tool_use"} → TOOL_USE</li>
 *   <li>{@code {"type":"user","message":{"content":[{"type":"tool_result",...}]}}} → TOOL_RESULT (the tool's returned text)</li>
 *   <li>{@code {"type":"result","result":...,"is_error":...}} → RESULT (or ERROR)</li>
 * </ul>
 */
public class ClaudeStreamParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStreamParser.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** A parsed, normalised event with its text/JSON payload, and the turn cost on a result line. */
    /**
     * A parsed event. On a result line, {@code costUsd} is the turn cost and
     * {@code permissionDenials} names tool calls the CLI refused without asking — in
     * {@code -p} mode there is no one to approve them, so they'd otherwise be invisible.
     */
    public record ParsedEvent(AgentEventType type, String payload, Double costUsd, List<String> permissionDenials) {
        public ParsedEvent(AgentEventType type, String payload) {
            this(type, payload, null, List.of());
        }

        public ParsedEvent(AgentEventType type, String payload, Double costUsd) {
            this(type, payload, costUsd, List.of());
        }
    }

    public Optional<ParsedEvent> parse(String line) {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception e) {
            log.debug("Skipping unparseable line: {}", e.getMessage());
            return Optional.empty();
        }
        String type = root.path("type").asText("");
        return switch (type) {
            case "system" -> parseSystem(root);
            case "stream_event" -> parseStreamEvent(root.path("event"));
            case "user" -> parseUser(root);
            case "result" -> parseResult(root);
            default -> Optional.empty();
        };
    }

    private Optional<ParsedEvent> parseSystem(JsonNode root) {
        if ("init".equals(root.path("subtype").asText(""))) {
            String sessionId = root.path("session_id").asText("");
            return Optional.of(new ParsedEvent(AgentEventType.SYSTEM, sessionId));
        }
        return Optional.empty();
    }

    private Optional<ParsedEvent> parseStreamEvent(JsonNode event) {
        String eventType = event.path("type").asText("");
        switch (eventType) {
            case "content_block_delta" -> {
                JsonNode delta = event.path("delta");
                String deltaType = delta.path("type").asText("");
                if ("text_delta".equals(deltaType)) {
                    return Optional.of(new ParsedEvent(AgentEventType.TEXT, delta.path("text").asText("")));
                }
                if ("thinking_delta".equals(deltaType)) {
                    return Optional.of(new ParsedEvent(AgentEventType.THINKING, delta.path("thinking").asText("")));
                }
                return Optional.empty();
            }
            case "content_block_start" -> {
                JsonNode block = event.path("content_block");
                if ("tool_use".equals(block.path("type").asText(""))) {
                    String name = block.path("name").asText("");
                    return Optional.of(new ParsedEvent(AgentEventType.TOOL_USE, name));
                }
                return Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * A tool's return value arrives as a {@code user} message carrying a
     * {@code tool_result} content block; surface its text so the chat UI can act
     * on it (e.g. turn a create_task result into a clickable link). The block's
     * {@code content} is either a plain string or an array of text blocks.
     */
    private Optional<ParsedEvent> parseUser(JsonNode root) {
        JsonNode content = root.path("message").path("content");
        if (!content.isArray()) {
            return Optional.empty();
        }
        for (JsonNode block : content) {
            if ("tool_result".equals(block.path("type").asText(""))) {
                String text = toolResultText(block.path("content"));
                if (!text.isBlank()) {
                    return Optional.of(new ParsedEvent(AgentEventType.TOOL_RESULT, text));
                }
            }
        }
        return Optional.empty();
    }

    private String toolResultText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                if ("text".equals(b.path("type").asText(""))) {
                    sb.append(b.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private Optional<ParsedEvent> parseResult(JsonNode root) {
        boolean isError = root.path("is_error").asBoolean(false);
        String text = root.path("result").asText("");
        if (isError && text.isBlank()) {
            // Error results carry the reason in "errors" (e.g. "No conversation found with session ID: …"),
            // not in "result"; without this the failure surfaces as an empty error.
            List<String> errors = new ArrayList<>();
            root.path("errors").forEach(e -> errors.add(e.isTextual() ? e.asText() : e.toString()));
            text = errors.isEmpty() ? root.path("subtype").asText("error") : String.join("; ", errors);
        }
        Double cost = root.has("total_cost_usd") ? root.path("total_cost_usd").asDouble() : null;
        List<String> denials = new ArrayList<>();
        root.path("permission_denials").forEach(d -> denials.add(describeDenial(d)));
        return Optional.of(new ParsedEvent(
                isError ? AgentEventType.ERROR : AgentEventType.RESULT, text, cost, denials));
    }

    private static String describeDenial(JsonNode d) {
        String tool = d.path("tool_name").asText("tool");
        String command = d.path("tool_input").path("command").asText("");
        String file = d.path("tool_input").path("file_path").asText("");
        String detail = !command.isBlank() ? command : file;
        if (detail.length() > 120) {
            detail = detail.substring(0, 117) + "...";
        }
        return detail.isBlank() ? tool : tool + ": " + detail;
    }
}
