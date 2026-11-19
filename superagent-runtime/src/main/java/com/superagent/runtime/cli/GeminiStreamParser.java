package com.superagent.runtime.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.usage.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Gemini CLI {@code --output-format stream-json} events into {@link JsonLineCliRunner.Line}s.
 * Event types: {@code init}, {@code message}, {@code tool_use}, {@code tool_result}, {@code error}, {@code result}.
 */
final class GeminiStreamParser implements JsonLineCliRunner.LineParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode stats;
    private String lastAssistant = "";

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
            case "init" -> {
                String sessionId = root.path("session_id").asText("");
                yield sessionId.isBlank() ? List.of()
                        : List.of(new JsonLineCliRunner.Line(AgentEventType.SYSTEM, sessionId));
            }
            case "message" -> {
                if (!"assistant".equals(root.path("role").asText(""))) {
                    yield List.of();
                }
                String content = root.path("content").asText("");
                if (content.isEmpty()) {
                    yield List.of();
                }
                // Streamed chunks: emit only the delta when content grows.
                String delta = content.startsWith(lastAssistant) ? content.substring(lastAssistant.length()) : content;
                lastAssistant = content;
                yield delta.isEmpty() ? List.of()
                        : List.of(new JsonLineCliRunner.Line(AgentEventType.TEXT, delta));
            }
            case "tool_use" -> {
                String name = root.path("tool_name").asText(root.path("name").asText("tool"));
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.TOOL_USE, name));
            }
            case "tool_result" -> {
                String out = root.path("output").asText("");
                if (out.isBlank() && root.path("error").isObject()) {
                    out = root.path("error").path("message").asText("tool error");
                }
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.TOOL_RESULT, out));
            }
            case "error" -> {
                String msg = root.path("error").path("message").asText(root.path("message").asText("gemini error"));
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.ERROR, msg));
            }
            case "result" -> {
                if (root.path("stats").isObject()) {
                    stats = root.path("stats");
                }
                if ("error".equals(root.path("status").asText(""))) {
                    String msg = root.path("error").path("message").asText("gemini failed");
                    yield List.of(new JsonLineCliRunner.Line(AgentEventType.ERROR, msg));
                }
                String text = lastAssistant.isBlank() ? root.path("response").asText("") : lastAssistant;
                yield List.of(new JsonLineCliRunner.Line(AgentEventType.RESULT, text));
            }
            default -> List.of();
        };
    }

    @Override
    public List<TokenUsage> tokens(String model) {
        if (stats == null) {
            return List.of();
        }
        // Gemini reports models.<id>.{prompt,candidates,…} or flat input/output under stats.
        List<TokenUsage> out = new ArrayList<>();
        JsonNode models = stats.path("models");
        if (models.isObject() && !models.isEmpty()) {
            models.fields().forEachRemaining(e -> {
                JsonNode m = e.getValue();
                long in = Tokens.n(m, "promptTokenCount") + Tokens.n(m, "prompt");
                long cached = Tokens.n(m, "cachedContentTokenCount");
                long write = Tokens.n(m, "cacheCreationTokenCount");
                long output = Tokens.n(m, "candidatesTokenCount") + Tokens.n(m, "candidates")
                        + Tokens.n(m, "outputTokenCount");
                out.add(new TokenUsage(e.getKey(), Math.max(0, in - cached - write), cached, write, 0, output, 0, null));
            });
            return out;
        }
        long in = Tokens.n(stats, "inputTokens") + Tokens.n(stats, "promptTokenCount");
        long output = Tokens.n(stats, "outputTokens") + Tokens.n(stats, "candidatesTokenCount");
        if (in == 0 && output == 0) {
            return List.of();
        }
        return List.of(new TokenUsage(model, in, 0, 0, 0, output, 0, null));
    }
}
