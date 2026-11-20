package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.superagent.core.usage.TokenUsage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * A parsed event. On a result line, {@code permissionDenials} names tool calls the CLI refused without asking — in
     * {@code -p} mode there is no one to approve them, so they'd otherwise be invisible — and {@code usage} is what the
     * result reports about tokens and cost (see {@link ClaudeUsageLedger} for turning it into this turn's share).
     */
    public record ParsedEvent(AgentEventType type, String payload, List<String> permissionDenials, ResultUsage usage) {

        public ParsedEvent(AgentEventType type, String payload) {
            this(type, payload, List.of(), null);
        }
    }

    /**
     * What a result reports. Checked against real runs (Claude Code 2.1.318, recorded in test resources {@code usage/}):
     * {@code modelUsage} and
     * {@code total_cost_usd} are running totals for the whole conversation, across {@code --resume} and kept
     * processes alike; {@code model_usage_since_last_result} (newer CLIs only) is this turn's, per model, the CLI's own
     * side calls included. {@code calls} is the sum of this turn's API calls as the assistant messages carried them.
     */
    public record ResultUsage(String sessionId, List<TokenUsage> sinceLast, Double sinceLastCostUsd,
                              List<TokenUsage> cumulative, Double totalCostUsd, List<TokenUsage> calls) {
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
            case "assistant" -> {
                noteUsage(root); // shown through the stream events; kept only for its usage
                yield Optional.empty();
            }
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

    // --- tokens ---

    /**
     * Each API call's usage as the assistant messages carry it. A message comes once per content block, each carrying
     * the call's final usage (recorded: text then tool_use, both output 91); keyed by id, so it's counted once.
     */
    private final Map<String, JsonNode> messageUsage = new LinkedHashMap<>();
    private final Map<String, String> messageModel = new HashMap<>();
    private boolean resultSeen;

    private void noteUsage(JsonNode root) {
        JsonNode message = root.path("message");
        JsonNode usage = message.path("usage");
        String id = message.path("id").asText("");
        if (!usage.isObject() || id.isEmpty()) {
            return;
        }
        messageUsage.put(id, usage);
        messageModel.put(id, message.path("model").asText(null));
    }

    private static List<TokenUsage> byModel(JsonNode byModel) {
        if (!byModel.isObject()) {
            return null;
        }
        List<TokenUsage> out = new ArrayList<>();
        byModel.fields().forEachRemaining(e -> {
            JsonNode m = e.getValue();
            out.add(new TokenUsage(e.getKey(), Tokens.n(m, "inputTokens"), Tokens.n(m, "cacheReadInputTokens"),
                    Tokens.n(m, "cacheCreationInputTokens"), Tokens.n(m, "cacheCreationEphemeral1hInputTokens"),
                    Tokens.n(m, "outputTokens"), Tokens.n(m, "thinkingTokens"),
                    m.path("costUSD").isNumber() ? m.path("costUSD").asDouble() : null));
        });
        return out;
    }

    /** The calls this run made so far, per model the messages named, each counted once. */
    private List<TokenUsage> callTokens() {
        Map<String, long[]> sum = new LinkedHashMap<>();
        messageUsage.forEach((id, u) -> {
            long[] t = sum.computeIfAbsent(String.valueOf(messageModel.get(id)), k -> new long[6]);
            t[0] += Tokens.n(u, "input_tokens");
            t[1] += Tokens.n(u, "cache_read_input_tokens");
            t[2] += Tokens.n(u, "cache_creation_input_tokens");
            t[3] += Tokens.n(u.path("cache_creation"), "ephemeral_1h_input_tokens");
            t[4] += Tokens.n(u, "output_tokens");
            t[5] += Tokens.n(u.path("output_tokens_details"), "thinking_tokens");
        });
        List<TokenUsage> out = new ArrayList<>();
        sum.forEach((model, t) -> out.add(new TokenUsage("null".equals(model) ? null : model,
                t[0], t[1], t[2], t[3], t[4], t[5], null)));
        return out;
    }

    /**
     * What the calls made so far used, when the run ended without a result (stopped, timed out, crashed); empty once
     * a result reported the turn, so nothing is counted twice.
     */
    public List<TokenUsage> unreportedTokens() {
        return resultSeen ? List.of() : callTokens();
    }

    private ResultUsage resultUsage(JsonNode root) {
        List<TokenUsage> sinceLast = byModel(root.path("model_usage_since_last_result"));
        Double sinceLastCost = sinceLast == null || sinceLast.stream().anyMatch(t -> t.cliCostUsd() == null) ? null
                : sinceLast.stream().mapToDouble(TokenUsage::cliCostUsd).sum();
        List<TokenUsage> cumulative = byModel(root.path("modelUsage"));
        Double total = root.path("total_cost_usd").isNumber() ? root.path("total_cost_usd").asDouble() : null;
        List<TokenUsage> calls = callTokens();
        JsonNode u = root.path("usage");
        if (calls.isEmpty() && u.isObject()) {
            // No assistant message seen (e.g. the CLI failed before streaming): the result's usage is this turn's main loop.
            calls = List.of(new TokenUsage(null, Tokens.n(u, "input_tokens"), Tokens.n(u, "cache_read_input_tokens"),
                    Tokens.n(u, "cache_creation_input_tokens"), Tokens.n(u.path("cache_creation"), "ephemeral_1h_input_tokens"),
                    Tokens.n(u, "output_tokens"), Tokens.n(u.path("output_tokens_details"), "thinking_tokens"), null));
        }
        if (cumulative != null && cumulative.size() == 1) {
            // Through a relay a message may name the model its own way (seen: "MaaS_Cl_Sonnet_5.5_…" for
            // claude-sonnet-5-5); with one model in the result, that's the one it priced.
            String model = cumulative.get(0).model();
            calls = List.of(calls.stream().reduce(new TokenUsage(model, 0, 0, 0, 0, 0, 0, null), (a, b) -> new TokenUsage(model,
                    a.input() + b.input(), a.cacheRead() + b.cacheRead(), a.cacheWrite() + b.cacheWrite(),
                    a.cacheWrite1h() + b.cacheWrite1h(), a.output() + b.output(), a.reasoning() + b.reasoning(), null)));
        }
        String session = root.path("session_id").asText("");
        return new ResultUsage(session.isEmpty() ? null : session, sinceLast, sinceLastCost,
                cumulative == null ? List.of() : cumulative, total, calls);
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
        List<String> denials = new ArrayList<>();
        root.path("permission_denials").forEach(d -> denials.add(describeDenial(d)));
        ResultUsage usage = resultUsage(root);
        resultSeen = true;
        return Optional.of(new ParsedEvent(isError ? AgentEventType.ERROR : AgentEventType.RESULT, text, denials, usage));
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
