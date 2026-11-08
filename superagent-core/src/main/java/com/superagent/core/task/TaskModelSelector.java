package com.superagent.core.task;

import java.util.Optional;

/**
 * Picks the model a task runs on: good enough for the job, as cheap as can be. Cost is what this is for, so a task
 * starts on the cheapest model judged able to do it, and moves up only when that model fails. Implemented by the
 * cost module; tasks only see this. Only in use while model tiers are on.
 */
public interface TaskModelSelector {

    boolean enabled();

    /**
     * Judges the task about to run and picks its model; never empty while enabled and some model can run (when the
     * judging fails the result says so and falls back to the default tier). Takes a while: it asks a model.
     */
    Optional<TaskModelChoice> choose(Task task);

    /** The next model up after the task failed on its current one; empty when there's nothing stronger to try. */
    Optional<TaskModelChoice> escalate(Task task, String failure);

    /**
     * The model for work that has to be done well rather than cheaply (a complex task's plan and conclusion): the
     * default model, without judging. Empty when there's nothing to say (then {@link #choose} runs as usual).
     */
    default Optional<TaskModelChoice> strongest(Task task) {
        return Optional.empty();
    }
}
