package com.superagent.core.provider;

import java.util.Optional;

/**
 * Picks a model for the system's own small jobs (keeping an agent's memory, tagging files): the cheapest one that can
 * run now, by list price. Conversations run on the admin's default model; tasks get theirs chosen for the job.
 * Implemented by the cost module; the rest only see this.
 */
public interface ModelRouter {

    /** The cheapest model that can run now; empty when none can (callers then use the default model). */
    Optional<ModelChoice> cheapest();

    /** {@code model} runs on CLI {@code cli}. */
    record ModelChoice(String model, String cli) {
    }
}
