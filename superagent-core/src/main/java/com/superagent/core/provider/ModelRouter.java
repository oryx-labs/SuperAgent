package com.superagent.core.provider;

import java.util.List;
import java.util.Optional;

/**
 * Which model to run on. Conversations run on the admin's default model, and when it can't run, on a stand-in the
 * user never has to know about; the system's own small jobs (keeping an agent's memory, tagging files) on the
 * cheapest model. Implemented by the cost module; the rest only see this.
 */
public interface ModelRouter {

    /** The cheapest model that can run now; empty when none can (callers then use the default model). */
    Optional<ModelChoice> cheapest();

    /**
     * The models a conversation may run on, in order: the default model while it works, then the admin's backups,
     * then the rest that can run, strongest (dearest) first. Empty when no default model is set: the CLI's own then.
     */
    List<String> chatModels();

    /**
     * {@code model} just failed to run (its endpoint, key or the model itself): it's skipped for a while, then tried
     * again, so the default comes back on its own.
     */
    void reportFailure(String model, String error);

    /** {@code model} runs on CLI {@code cli}. */
    record ModelChoice(String model, String cli) {
    }
}
