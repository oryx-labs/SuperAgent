package com.superagent.core.automation;

import java.time.Instant;

/**
 * A saved prompt that runs as a task on its own, by schedule or by API call. Each firing
 * creates an ordinary task, so execution, retries and results are the task system's.
 *
 * <p>{@code cron} is set for {@link AutomationType#RECURRING} (5-field, in {@code timezone});
 * {@code runAt} for {@link AutomationType#ONCE}. {@code model} null means the system default.
 * {@code source} is "manual" (the form) or "chat" (created by an agent in a conversation).
 * {@code nextRunAt} is when the scheduler fires it next (null = not scheduled: paused, API, or a
 * spent one-off). Only a hash of the API token is kept.
 */
public record Automation(
        String id,
        String name,
        String prompt,
        AutomationType type,
        String cron,
        Instant runAt,
        String timezone,
        String model,
        OverlapPolicy overlap,
        boolean enabled,
        String source,
        String owner,
        String apiTokenHash,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastTaskId,
        Instant createdAt,
        Instant updatedAt) {

    public Automation withNextRun(Instant next) {
        return new Automation(id, name, prompt, type, cron, runAt, timezone, model, overlap, enabled, source, owner,
                apiTokenHash, next, lastRunAt, lastTaskId, createdAt, Instant.now());
    }

    public Automation withEnabled(boolean on, Instant next) {
        return new Automation(id, name, prompt, type, cron, runAt, timezone, model, overlap, on, source, owner,
                apiTokenHash, next, lastRunAt, lastTaskId, createdAt, Instant.now());
    }

    /** Records a firing; {@code taskId} null when it was skipped (the last real run stays the latest). */
    public Automation fired(Instant at, String taskId) {
        return new Automation(id, name, prompt, type, cron, runAt, timezone, model, overlap, enabled, source, owner,
                apiTokenHash, nextRunAt, at, taskId != null ? taskId : lastTaskId, createdAt, Instant.now());
    }

    public Automation withApiTokenHash(String hash) {
        return new Automation(id, name, prompt, type, cron, runAt, timezone, model, overlap, enabled, source, owner,
                hash, nextRunAt, lastRunAt, lastTaskId, createdAt, Instant.now());
    }
}
