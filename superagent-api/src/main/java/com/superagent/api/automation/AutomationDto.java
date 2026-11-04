package com.superagent.api.automation;

import java.time.Instant;

/**
 * An automation for clients. {@code type}: recurring | once | api; {@code overlap}: skip | parallel;
 * {@code source}: manual | chat. {@code lastStatus} is the status of the task its latest run
 * started (null before the first run). {@code apiToken} is filled only in the response that
 * minted it (create / regenerate) — it is never stored or shown again.
 */
public record AutomationDto(
        String id,
        String name,
        String prompt,
        String type,
        String cron,
        Instant runAt,
        String timezone,
        String model,
        String overlap,
        boolean enabled,
        String source,
        String owner,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastTaskId,
        String lastStatus,
        String triggerPath,
        String apiToken,
        Instant createdAt,
        Instant updatedAt) {
}
