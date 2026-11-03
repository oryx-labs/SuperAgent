package com.superagent.api.task;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A task for list/summary views. Enum fields are lowercase strings; the heavy
 * {@code brief} and the execution stream live in {@link TaskDetailDto}.
 */
public record TaskDto(
        String id,
        String title,
        String source,
        String repo,
        String branch,
        String flow,
        String status,
        int progress,
        BigDecimal spend,
        String model,
        String createdBy,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {
}
