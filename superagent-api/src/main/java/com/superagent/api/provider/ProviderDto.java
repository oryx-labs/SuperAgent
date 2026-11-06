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
        /** Tasks and chat run on it (model list, inference checks). */
        boolean supported,
        /** The Models page can install and upgrade it (supported ones, plus install-only CLIs). */
        boolean installable,
        /** The vendor's release isn't stable yet. */
        boolean preview,
        /** Lowest Node.js the CLI accepts, or null when it doesn't need Node. */
        String minNode,
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
        /** What a running install/upgrade is doing now (e.g. downloading Node.js), else null. */
        String installStep,
        /** There's a log of the last install/upgrade to show. */
        boolean hasInstallLog,
        Instant lastCheckedAt,
        Instant createdAt) {
}
