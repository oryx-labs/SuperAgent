package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.usage.TokenUsage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses MiniMax Code {@code mcode exec --output-format stream-json} events into {@link JsonLineCliRunner.Line}s.
 * Wire shape (mcode 0.7.x): {@code session.started|resumed}, {@code item.*} ({@code agent_message}/{@code tool_call}),
 * {@code turn.failed}, {@code exec.completed} with {@code result.output} / {@code result.usage}.
 */
final class McodeStreamParser implements JsonLineCliRunner.LineParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Set<String> announcedTools = new HashSet<>();
    private String lastMessage = "";
    private JsonNode usage;

    @Override
    public List<JsonLineCliRunner.Line> parse(String line) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (Exception e) {
            return List.of();
        }
        String type = root.path("type").asText("");
        return switch (type) {
            case "session.started", "session.resumed" -> {
                String sessionId = root.path("sessionId").asText("");
                yield sessionId.isBlank() ? List.of()
                        : List.of(new JsonLineCliRunner.Line(AgentEventType.SYSTEM, sessionId));
            }
            case "item.started", "item.updated" -> announceTool(root.path("item"));
            case "item.completed" -> itemCompleted(root.path("item"));
            case "turn.failed" -> List.of(new JsonLineCliRunner.Line(AgentEventType.ERROR,
                    root.path("error").path("message").asText("mcode turn failed")));
            case "exec.completed" -> {
                JsonNode result = root.path("result");
                if (result.path("usage").isObject()) {
                    usage = result.path("usage");
                }
                String status = result.path("status").asText(root.path("status").asText(""));
                if ("failed".equals(status) || "error".equals(status)) {
                    String msg = result.path("error").path("message")
                            .asText(root.path("error").path("message").asText("mcode failed"));
                    yield List.of(new JsonLineCliRunner.Line(AgentEventType.ERROR, msg));
                }
                String out = result.path("output").asText(lastMessage);
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.RESULT, out == null ? "" : out));
            }
            default -> List.of(); // exec.started, turn.started, turn.completed, …
        };
    }

    private List<JsonLineCliRunner.Line> itemCompleted(JsonNode item) {
        String itemType = item.path("type").asText("");
        return switch (itemType) {
            case "agent_message", "assistant_message", "agent_message_chunk" -> {
                String text = item.path("text").asText(item.path("content").asText(""));
                if (text.isBlank() && item.path("contentDelta").isTextual()) {
                    text = item.path("contentDelta").asText("");
                }
                if (text.isBlank()) {
                    yield List.of();
                }
                lastMessage = text;
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.TEXT, text));
            }
            case "tool_call" -> {
                List<JsonLineCliRunner.Line> out = new ArrayList<>(announceTool(item));
                JsonNode call = item.path("toolCall");
                String result = call.path("result").asText(call.path("output").asText(""));
                if (!result.isBlank()) {
                    out.add(new JsonLineCliRunner.Line(AgentEventType.TOOL_RESULT, result));
                }
                yield out;
            }
            default -> announceTool(item);
        };
    }

    private List<JsonLineCliRunner.Line> announceTool(JsonNode item) {
        if (!"tool_call".equals(item.path("type").asText(""))) {
            return List.of();
        }
        String id = item.path("id").asText("");
        if (!id.isBlank() && !announcedTools.add(id)) {
            return List.of();
        }
        JsonNode call = item.path("toolCall");
        String name = call.path("name").asText(call.path("tool").asText(
                item.path("name").asText("tool")));
        return List.of(new JsonLineCliRunner.Line(AgentEventType.TOOL_USE, name));
    }

    @Override
    public List<TokenUsage> tokens(String model) {
        if (usage == null) {
            return List.of();
        }
        long in = Tokens.n(usage, "input_tokens") + Tokens.n(usage, "inputTokens")
                + Tokens.n(usage, "prompt_tokens");
        long cached = Tokens.n(usage, "cached_input_tokens") + Tokens.n(usage, "cache_read_input_tokens");
        long output = Tokens.n(usage, "output_tokens") + Tokens.n(usage, "outputTokens")
                + Tokens.n(usage, "completion_tokens");
        long reasoning = Tokens.n(usage, "reasoning_tokens") + Tokens.n(usage, "reasoning_output_tokens");
        if (in == 0 && output == 0) {
            return List.of();
        }
        return List.of(Tokens.fromTotalInput(model, in, cached, 0, output, reasoning));
    }
}
