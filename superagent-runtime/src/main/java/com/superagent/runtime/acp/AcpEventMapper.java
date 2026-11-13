package com.superagent.runtime.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.usage.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an ACP {@code session/update} into the events SuperAgent shows.
 *
 * <p>ACP's update kinds, as Grok's agent sends them:
 * <ul>
 *   <li>{@code agent_message_chunk} → TEXT (the reply, in pieces)</li>
 *   <li>{@code agent_thought_chunk} → THINKING (the reasoning)</li>
 *   <li>{@code tool_call} → TOOL_USE (the tool and its input)</li>
 *   <li>{@code tool_call_update} → TOOL_RESULT once the call has an output</li>
 *   <li>{@code plan} → TEXT (the plan the agent is following)</li>
 * </ul>
 *
 * <p>The xAI-specific notifications ({@code _x.ai/…}) carry retries, session bookkeeping
 * and the turn's usage. Only the usage is worth surfacing: it is what the run is billed for.
 */
public final class AcpEventMapper {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One normalised event, plus the tokens the update reported (empty when it reported none). */
    public record Mapped(AgentEventType type, String payload, List<TokenUsage> tokens) {
    }

    private AcpEventMapper() {
    }

    /**
     * Maps one {@code session/update} notification. Empty when the update says nothing worth showing.
     *
     * <p>A {@code usage} update carries the turn's tokens; it is not shown, but the caller
     * bills it (see {@link #mapUsage}).
     */
    public static List<Mapped> mapUpdate(JsonNode params) {
        JsonNode update = params.path("update");
        String kind = update.path("sessionUpdate").asText("");
        return switch (kind) {
            case "agent_message_chunk" -> {
                String text = text(update.path("content"));
                yield text.isEmpty() ? List.of() : List.of(new Mapped(AgentEventType.TEXT, text, List.of()));
            }
            case "agent_thought_chunk" -> {
                String text = text(update.path("content"));
                yield text.isEmpty() ? List.of() : List.of(new Mapped(AgentEventType.THINKING, text, List.of()));
            }
            case "tool_call" -> {
                String label = toolLabel(update);
                yield label.isEmpty() ? List.of() : List.of(new Mapped(AgentEventType.TOOL_USE, label, List.of()));
            }
            case "tool_call_update" -> {
                String output = toolOutput(update);
                yield output.isEmpty() ? List.of() : List.of(new Mapped(AgentEventType.TOOL_RESULT, output, List.of()));
            }
            case "plan" -> {
                String plan = plan(update);
                yield plan.isEmpty() ? List.of() : List.of(new Mapped(AgentEventType.TEXT, plan, List.of()));
            }
            default -> List.of();
        };
    }

    /**
     * Whether this update carries the turn's final usage. Grok emits
     * {@code turn_completed} (canonical) and sometimes a bare {@code usage};
     * {@code response_completed} is a per-model-call partial and is ignored so we
     * do not double-bill.
     */
    public static boolean isUsageUpdate(JsonNode params) {
        String kind = params.path("update").path("sessionUpdate").asText("");
        return "turn_completed".equals(kind) || "usage".equals(kind);
    }

    /**
     * The usage Grok reports for a finished turn. Empty when it reported none.
     *
     * <p>Two shapes reach us. The ACP {@code turn_completed} update carries the camelCase
     * buckets ({@code inputTokens}, {@code outputTokens}, {@code totalTokens},
     * {@code cachedReadTokens}, {@code cacheCreationTokens}, {@code reasoningTokens});
     * the mid-turn {@code response_completed} carries the snake_case ones
     * ({@code input_tokens} and friends), where {@code input_tokens} is the uncached part.
     * Usage may sit on {@code params.usage} or nested under {@code params.update.usage}.
     */
    public static List<TokenUsage> mapUsage(JsonNode params, String model) {
        JsonNode usage = params.path("usage");
        if (!usage.isObject()) {
            usage = params.path("update").path("usage");
        }
        if (!usage.isObject()) {
            return List.of();
        }
        long input = number(usage, "inputTokens", "input_tokens");
        // Grok's ACP field is cachedReadTokens; cacheReadTokens is the ACP-generic spelling.
        long cacheRead = number(usage, "cachedReadTokens", "cacheReadTokens", "cache_read_input_tokens");
        long cacheWrite = number(usage, "cacheCreationTokens", "cache_creation_input_tokens");
        long output = number(usage, "outputTokens", "output_tokens");
        long reasoning = number(usage, "reasoningTokens", "reasoning_tokens");
        long total = number(usage, "totalTokens", "total_tokens");
        if (total > 0 && input + cacheRead + cacheWrite == 0) {
            // Only a total was given: everything beyond the output is prompt-side, uncached.
            input = Math.max(0, total - output);
        }
        if (input + cacheRead + cacheWrite + output == 0) {
            return List.of();
        }
        return List.of(new TokenUsage(model, input, cacheRead, cacheWrite, 0, output, reasoning, null));
    }

    /** The cost Grok reports for a turn, when it reported one. */
    public static Double mapCost(JsonNode params) {
        JsonNode cost = params.path("costUsd");
        if (cost.isNumber()) {
            return cost.asDouble();
        }
        cost = params.path("cost");
        if (cost.isNumber()) {
            return cost.asDouble();
        }
        cost = params.path("total_cost_usd");
        return cost.isNumber() ? cost.asDouble() : null;
    }

    /** The text of a content block: ACP's {@code text} field, or its nested text block. */
    private static String text(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isObject()) {
            return "";
        }
        if (content.has("text")) {
            return content.path("text").asText("");
        }
        if ("image".equals(content.path("type").asText(""))) {
            return "[image]";
        }
        return "";
    }

    /** A tool call as it is shown: its title, and the input that says what it will do. */
    private static String toolLabel(JsonNode update) {
        String title = update.path("title").asText("");
        String kind = update.path("kind").asText("");
        String toolName = update.path("toolName").asText("");
        JsonNode input = update.path("rawInput");
        String detail = detail(input);
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) {
            sb.append(title);
        } else if (!toolName.isBlank()) {
            sb.append(toolName);
        } else {
            sb.append(kind.isBlank() ? "tool" : kind);
        }
        if (!detail.isBlank()) {
            sb.append(": ").append(detail);
        }
        return sb.toString();
    }

    /** The tool's output, when it has one. */
    private static String toolOutput(JsonNode update) {
        JsonNode output = update.path("rawOutput");
        if (output.isMissingNode() || output.isNull()) {
            return "";
        }
        String status = update.path("status").asText("");
        String text = output.isTextual() ? output.asText() : output.toString();
        if ("failed".equals(status)) {
            return "Failed: " + text;
        }
        return text;
    }

    /** The plan, as a few readable lines. */
    private static String plan(JsonNode update) {
        JsonNode entries = update.path("entries");
        if (!entries.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode entry : entries) {
            String content = entry.path("content").asText("");
            if (!content.isBlank()) {
                sb.append("- ").append(content.strip()).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /**
     * The one line of an input that says what the tool will do. Grok's MCP meta-tool
     * {@code use_tool} wraps the real tool as {@code tool_name} + {@code tool_input}.
     */
    private static String detail(JsonNode input) {
        if (!input.isObject()) {
            return "";
        }
        String toolName = input.path("tool_name").asText("");
        if (!toolName.isBlank()) {
            String nested = detail(input.path("tool_input"));
            String label = nested.isBlank() ? toolName : toolName + " " + nested;
            return label.length() > 160 ? label.substring(0, 157) + "..." : label;
        }
        for (String field : List.of("command", "cmd", "path", "file_path", "pattern", "query", "url", "text")) {
            String value = input.path(field).asText("");
            if (!value.isBlank()) {
                return value.length() > 160 ? value.substring(0, 157) + "..." : value;
            }
        }
        return "";
    }

    private static long number(JsonNode node, String... fields) {
        for (String field : fields) {
            if (field == null) {
                continue;
            }
            JsonNode value = node.path(field);
            if (value.canConvertToLong()) {
                return Math.max(0, value.asLong());
            }
        }
        return 0;
    }

    /** The permission an agent asks for, and the options it offers. */
    public record PermissionRequest(String toolCallId, String title, List<Option> options) {
        public record Option(String optionId, String name, String kind) {
        }
    }

    /** Reads a {@code session/request_permission} request. */
    public static PermissionRequest mapPermission(JsonNode params) {
        JsonNode options = params.path("options");
        List<PermissionRequest.Option> list = new ArrayList<>();
        options.forEach(o -> list.add(new PermissionRequest.Option(o.path("optionId").asText(""),
                o.path("name").asText(""), o.path("kind").asText(""))));
        return new PermissionRequest(params.path("toolCallId").asText(""), params.path("title").asText(""), list);
    }

    /** The permission options Grok offers, so a caller can pick one by name rather than by position. */
    public static String optionFor(PermissionRequest request, boolean allow) {
        return optionFor(request, allow, false);
    }

    /** The option that answers the request; null when it offers none that fits. */
    public static String optionFor(PermissionRequest request, boolean allow, boolean always) {
        String wanted = allow
                ? (always ? AcpClient.PermissionDecision.ALLOW_ALWAYS : AcpClient.PermissionDecision.ALLOW_ONCE)
                : (always ? AcpClient.PermissionDecision.REJECT_ALWAYS : AcpClient.PermissionDecision.REJECT_ONCE);
        for (PermissionRequest.Option option : request.options()) {
            if (wanted.equals(option.optionId())) {
                return option.optionId();
            }
        }
        // Some agents only offer a generic "allow"/"reject"; take the first that matches the intent.
        for (PermissionRequest.Option option : request.options()) {
            String id = option.optionId().toLowerCase(java.util.Locale.ROOT);
            if (allow && id.startsWith("allow")) {
                return option.optionId();
            }
            if (!allow && id.startsWith("reject")) {
                return option.optionId();
            }
        }
        return null;
    }

    /** Builds the {@code session/prompt} params. */
    public static ObjectNode prompt(String sessionId, String text) {
        ObjectNode params = JSON.createObjectNode();
        params.put("sessionId", sessionId);
        params.putArray("prompt").addObject().put("type", "text").put("text", text);
        return params;
    }
}
