package com.superagent.api.timing;

import java.time.Instant;
import java.util.List;

/**
 * Where one chat reply or task turn spent its time; the stages follow each other and add up to {@code totalMs}
 * (see core TurnTiming for what each one covers).
 */
public record TurnTimingDto(String messageId, String label, Instant startedAt, Instant finishedAt, long totalMs,
                            long prepMs, long startupMs, long modelMs, long toolsMs, long outputMs, long retryMs,
                            long saveMs, int attempts, List<Tool> tools) {

    public record Tool(String name, int count, long ms) {
    }

    public static TurnTimingDto of(com.superagent.core.timing.TurnTiming t) {
        return new TurnTimingDto(t.messageId(), t.label(), t.startedAt(), t.finishedAt(), t.totalMs(), t.prepMs(),
                t.startupMs(), t.modelMs(), t.toolsMs(), t.outputMs(), t.retryMs(), t.saveMs(), t.attempts(),
                t.tools().stream().map(x -> new Tool(x.name(), x.count(), x.ms())).toList());
    }
}
