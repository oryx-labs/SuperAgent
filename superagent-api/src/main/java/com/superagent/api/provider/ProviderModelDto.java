package com.superagent.api.provider;

import java.time.Instant;
import java.util.List;

/**
 * One model a CLI can run, as reported by the CLI or its credentials.
 *
 * @param efforts        selectable reasoning-effort levels; empty when unknown
 * @param defaultEffort  the CLI's default level, or null when not reported
 * @param effortSource   "model" when the levels came from this model's own capability data,
 *                       "cli" when only the CLI-wide accepted set is known (not verified per model),
 *                       "none" when nothing is known
 * @param contextWindow  context window in tokens, or null when not reported
 * @param nativeModel    false for models the CLI's vendor doesn't make (e.g. a gateway serving
 *                       GPT through Claude Code) — listed, but not verified to run
 * @param inferStatus    result of the last background inference probe: "ok", "error", or null
 *                       when not probed yet. Being listed doesn't mean a model can actually answer.
 * @param inferError     why the last probe failed, or null
 * @param inferCheckedAt when the last probe ran, or null
 */
public record ProviderModelDto(
        String id,
        String displayName,
        List<String> efforts,
        String defaultEffort,
        String effortSource,
        Integer contextWindow,
        boolean nativeModel,
        String inferStatus,
        String inferError,
        Instant inferCheckedAt) {

    public static final String INFER_OK = "ok";
    public static final String INFER_ERROR = "error";

    /** A freshly listed model, not probed yet. */
    public ProviderModelDto(String id, String displayName, List<String> efforts, String defaultEffort,
                            String effortSource, Integer contextWindow, boolean nativeModel) {
        this(id, displayName, efforts, defaultEffort, effortSource, contextWindow, nativeModel, null, null, null);
    }

    public ProviderModelDto withInference(String status, String error, Instant checkedAt) {
        return new ProviderModelDto(id, displayName, efforts, defaultEffort, effortSource, contextWindow,
                nativeModel, status, error, checkedAt);
    }

    public boolean canInfer() {
        return INFER_OK.equals(inferStatus);
    }
}

