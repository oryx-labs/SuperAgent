package com.superagent.runtime.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.usage.TokenUsage;
import com.superagent.core.usage.UsageRecorder;

import java.util.List;

/** Runners report a run's tokens the same way: one TOKENS event at the end, a JSON array of {@link TokenUsage}. */
public final class Tokens {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Tokens() {
    }

    /** Emits the run's usage, skipping models that used nothing; nothing at all when none did. */
    public static void emit(AgentEventSink sink, String sessionId, long seq, List<TokenUsage> usage) {
        List<TokenUsage> used = usage.stream().filter(u -> u != null && !u.nothingUsed()).toList();
        if (used.isEmpty()) {
            return;
        }
        try {
            sink.emit(UsageRecorder.event(sessionId, seq, JSON.writeValueAsString(used)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<TokenUsage> parse(String json) {
        try {
            return List.of(JSON.readValue(json, TokenUsage[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a token usage list: " + json, e);
        }
    }

    static long n(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.canConvertToLong() ? Math.max(0, v.asLong()) : 0;
    }

    /** Usage where input is reported as a total that includes cached tokens (OpenAI style): split it. */
    static TokenUsage fromTotalInput(String model, long totalInput, long cacheRead, long cacheWrite, long output,
                                     long reasoning) {
        return new TokenUsage(model, Math.max(0, totalInput - cacheRead - cacheWrite), cacheRead, cacheWrite, 0, output,
                reasoning, null);
    }
}
