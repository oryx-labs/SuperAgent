package com.superagent.core.timing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Where one run of a model went, for a chat reply or a task turn, so anyone can see what a slow answer spent its
 * time on. The stages follow each other and add up to the whole: {@code prep} (getting the turn ready on our side,
 * queueing included), {@code startup} (the CLI starting until its first event), {@code model} (from there until the
 * model starts answering), {@code tools} (running the tools it called), {@code output} (the rest of the model's
 * time: thinking and writing), {@code retry} (attempts that failed and were run again) and {@code save} (after).
 *
 * @param label what the turn was, e.g. a coding phase ("prd"); null for a plain one
 */
public record TurnTiming(String sessionId, String messageId, String label, Instant startedAt, Instant finishedAt,
                         long prepMs, long startupMs, long modelMs, long toolsMs, long outputMs, long retryMs,
                         long saveMs, int attempts, List<ToolTime> tools) {

    public TurnTiming {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** One tool's calls in the turn. */
    public record ToolTime(String name, int count, long ms) {
    }

    public long totalMs() {
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}
