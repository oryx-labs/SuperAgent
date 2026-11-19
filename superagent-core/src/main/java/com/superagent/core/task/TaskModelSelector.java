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

    /** What a second model looks at a task's work for. */
    enum ReviewPurpose {
        /** Reviewing code and accepting it against its spec: the strongest model, from another vendor where possible. */
        REVIEW,
        /** Checking a plain task's output: easier, so the cheapest other model. */
        CHECK
    }

    /**
     * The model that looks at the work {@code task} did on its own model ({@link Task#model()}): never that one while
     * any other can run (on one of {@code runnableClis}); within the fixed models when the admin holds tasks to those.
     * The task's own model only when it is the only one, and the reason says so. Empty when nothing can be said.
     */
    default java.util.Optional<TaskModelChoice> reviewer(Task task, ReviewPurpose purpose, java.util.Set<String> runnableClis) {
        return java.util.Optional.empty();
    }

    /** The model that checks a plain task's output: the cheapest other model (see {@link #reviewer}, CHECK). */
    default Optional<TaskModelChoice> checker(Task task, java.util.Set<String> runnableClis) {
        return reviewer(task, ReviewPurpose.CHECK, runnableClis);
    }
}
