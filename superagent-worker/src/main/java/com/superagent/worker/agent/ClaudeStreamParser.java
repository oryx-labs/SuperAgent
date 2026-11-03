package com.superagent.worker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@code {"type":"result","result":...,"is_error":...}} → RESULT (or ERROR)</li>
 * </ul>
 */
public class ClaudeStreamParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeStreamParser.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /** A parsed, normalised event with its text/JSON payload, and the turn cost on a result line. */
    public record ParsedEvent(AgentEventType type, String payload, Double costUsd) {
        public ParsedEvent(AgentEventType type, String payload) {
            this(type, payload, null);
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

    private Optional<ParsedEvent> parseResult(JsonNode root) {
        boolean isError = root.path("is_error").asBoolean(false);
        String text = root.path("result").asText("");
        Double cost = root.has("total_cost_usd") ? root.path("total_cost_usd").asDouble() : null;
        return Optional.of(new ParsedEvent(
                isError ? AgentEventType.ERROR : AgentEventType.RESULT, text, cost));
    }
}
