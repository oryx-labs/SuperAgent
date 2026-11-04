package com.superagent.core.provider;

import java.time.Instant;

/**
 * A managed model-vendor CLI: which catalogue entry the user added, plus its live
 * status (refreshed by the background checker). Static spec — name, binary, install
 * command — comes from {@link ProviderCatalog} by {@link #providerKey()}; only live
 * state lives here, so executable commands are never stored in the DB.
 *
 * <p>{@code status}: unsupported | not_installed | no_auth | ok | needs_upgrade.
 * {@code modelsJson} is the fetched model list as raw JSON (core stays free of a
 * JSON library; the web layer owns the shape). {@code modelsError} is the last fetch
 * failure — the previous good list is kept alongside it.
 */
public record ModelProvider(
        String id,
        String providerKey,
        boolean installed,
        boolean canInfer,
        String currentVersion,
        String latestVersion,
        String status,
        Instant lastCheckedAt,
        String modelsJson,
        String modelsError,
        Instant modelsCheckedAt,
        Instant createdAt,
        Instant updatedAt) {

    public ModelProvider withStatus(boolean installed, boolean canInfer, String currentVersion,
                                    String latestVersion, String status) {
        return new ModelProvider(id, providerKey, installed, canInfer, currentVersion, latestVersion,
                status, Instant.now(), modelsJson, modelsError, modelsCheckedAt, createdAt, Instant.now());
    }

    public ModelProvider withLatestVersion(String newLatest) {
        return new ModelProvider(id, providerKey, installed, canInfer, currentVersion, newLatest,
                status, lastCheckedAt, modelsJson, modelsError, modelsCheckedAt, createdAt, Instant.now());
    }

    /** Records a model-list fetch: a new list (error cleared), or an error that keeps the last good list. */
    public ModelProvider withModels(String newModelsJson, String newModelsError) {
        return new ModelProvider(id, providerKey, installed, canInfer, currentVersion, latestVersion, status,
                lastCheckedAt, newModelsJson != null ? newModelsJson : modelsJson, newModelsError,
                Instant.now(), createdAt, Instant.now());
    }

    /** Rewrites the stored list in place (e.g. per-model probe results) without counting as a new fetch. */
    public ModelProvider withModelsJson(String newModelsJson) {
        return new ModelProvider(id, providerKey, installed, canInfer, currentVersion, latestVersion, status,
                lastCheckedAt, newModelsJson, modelsError, modelsCheckedAt, createdAt, Instant.now());
    }
}
