package com.superagent.core.usage;

import java.time.Instant;

/**
 * One model's tokens in one run, on the person who pays. {@code costUsd} is the estimate at the time, from list
 * prices (null when the model has no known price); {@code cliCostUsd} what the CLI said, when it says.
 */
public record UsageRecord(String id, Instant at, String user, String source, String agentId, String taskId,
                          String sessionId, String cli, String model, long input, long cacheRead, long cacheWrite,
                          long cacheWrite1h, long output, long reasoning, Double costUsd, Double cliCostUsd) {

    public long total() {
        return input + cacheRead + cacheWrite + output;
    }
}
