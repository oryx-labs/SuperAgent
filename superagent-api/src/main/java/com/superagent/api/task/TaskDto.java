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
        Instant updatedAt,
        String agentType,
        /** The agent session the task runs in (resumed for every steering message), or null before it starts. */
        String sessionId,
        String workspaceDir,
        /**
         * This process's hold on that session: busy | reserved (paused for confirmation) | idle |
         * in_use (held by another task) | released (not loaded, e.g. after a restart — resumed on demand).
         * Null before the task starts.
         */
        String sessionState,
        /** The tier it ran at, when tiers are on; {@code model} is the model that tier meant. */
        String tier,
        /** The chat reply that set it off, in an agent's list of its tasks; null elsewhere. */
        String originMessageId) {
}
