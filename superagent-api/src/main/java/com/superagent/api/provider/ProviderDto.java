package com.superagent.api.provider;

import java.time.Instant;
import java.util.List;

/**
 * A managed model-vendor CLI for the admin UI: catalogue spec (name, binary,
 * commands) merged with its live status and fetched model list. {@code supported=false}
 * entries are display-only — their status and models are not checked yet.
 */
public record ProviderDto(
        String id,
        String key,
        String name,
        String cli,
        boolean supported,
        boolean installed,
        boolean canInfer,
        String currentVersion,
        String latestVersion,
        boolean upgradeAvailable,
        String status,
        String installCommand,
        String upgradeCommand,
        List<ProviderModelDto> models,
        /** Where the model list came from (e.g. "codex debug models", "GET /v1/models"), or null. */
        String modelsSource,
        String modelsError,
        Instant modelsCheckedAt,
        /** Last install/upgrade failure (tail of its output), or null; survives status refreshes. */
        String message,
        Instant lastCheckedAt,
        Instant createdAt) {
}
