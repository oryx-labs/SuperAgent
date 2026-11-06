package com.superagent.core.provider;

import java.util.Optional;

/**
 * Turns a tier into a model, when the admin has turned tiers on. An interface here so agent and task can use it
 * without depending on the module that implements it (superagent-cost). While {@link #enabled()} is false callers
 * keep choosing models the way they always did.
 */
public interface ModelRouter {

    boolean enabled();

    /**
     * The model for {@code tier} (null = the system's default tier): {@code keep} if it still belongs there, so a
     * running conversation isn't moved to a new session just because a newer model appeared. Empty when no model
     * can run at all. An empty tier falls to a cheaper one first, and only then to a dearer one.
     */
    Optional<ModelChoice> choose(String tier, String keep);

    /** For the system's own chores (tagging files, distilling memory): the fast tier. */
    default Optional<ModelChoice> background() {
        return choose(ModelTier.FAST.key(), null);
    }

    /** {@code tier} is what was asked for, {@code model} what will run it, on CLI {@code cli}. */
    record ModelChoice(String tier, String model, String cli) {
    }
}
