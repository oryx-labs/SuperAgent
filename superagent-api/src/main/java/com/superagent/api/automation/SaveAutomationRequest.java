package com.superagent.api.automation;

/**
 * Create or update an automation. Only {@code prompt} is required; {@code type} defaults to
 * recurring. {@code cron} (5-field) is for recurring, {@code runAt} (wall-clock, e.g.
 * {@code 2026-11-05T15:00}, in {@code timezone}) for once. Blank {@code model} = system default;
 * blank {@code timezone} = Asia/Shanghai; null {@code enabled} = on.
 */
public record SaveAutomationRequest(
        String name,
        String prompt,
        String type,
        String cron,
        String runAt,
        String timezone,
        String model,
        String overlap,
        Boolean enabled,
        /** Used when tiers are on: null keeps it on an update, blank is the default tier. */
        String tier) {

    public SaveAutomationRequest(String name, String prompt, String type, String cron, String runAt, String timezone,
                                 String model, String overlap, Boolean enabled) {
        this(name, prompt, type, cron, runAt, timezone, model, overlap, enabled, null);
    }
}
