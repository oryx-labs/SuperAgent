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
 * @param inferFailures  consecutive failed probes (0 once one succeeds): how far a failing model's next check backs off
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
        Instant inferCheckedAt,
        int inferFailures) {

    public static final String INFER_OK = "ok";
    public static final String INFER_ERROR = "error";

    /** A freshly listed model, not probed yet. */
    public ProviderModelDto(String id, String displayName, List<String> efforts, String defaultEffort,
                            String effortSource, Integer contextWindow, boolean nativeModel) {
        this(id, displayName, efforts, defaultEffort, effortSource, contextWindow, nativeModel, null, null, null, 0);
    }

    /** This model with a given probe state, its failure count kept. */
    public ProviderModelDto withInference(String status, String error, Instant checkedAt) {
        return new ProviderModelDto(id, displayName, efforts, defaultEffort, effortSource, contextWindow,
                nativeModel, status, error, checkedAt, inferFailures);
    }

    /** This model with {@code other}'s probe state (a re-listed model keeps what was found out about it). */
    public ProviderModelDto withInferenceOf(ProviderModelDto other) {
        return new ProviderModelDto(id, displayName, efforts, defaultEffort, effortSource, contextWindow,
                nativeModel, other.inferStatus, other.inferError, other.inferCheckedAt, other.inferFailures);
    }

    /** The outcome of one probe: a success clears the failure count, a failure adds to it. */
    public ProviderModelDto checked(boolean ok, String error, Instant at) {
        return new ProviderModelDto(id, displayName, efforts, defaultEffort, effortSource, contextWindow,
                nativeModel, ok ? INFER_OK : INFER_ERROR, ok ? null : error, at, ok ? 0 : inferFailures + 1);
    }

    public boolean canInfer() {
        return INFER_OK.equals(inferStatus);
    }
}

