package com.superagent.task.harness;

import com.superagent.core.task.Task;

/**
 * A built-in way of running one kind of task (coding today; writing, reviewing and others to come): its fixed steps,
 * and for each how it is done and how the platform judges it. The agent does the work; the harness decides whether
 * a step passed, from what it can check itself, never from the agent's own account of its work.
 */
public interface TaskHarness {

    /** The task kind it runs (its storage name, e.g. {@code coding}). */
    String kind();

    /** A new task's state: its steps, all pending, and what it was set up with. */
    HarnessState start(Task task, String delivery);

    /**
     * Runs from the current step until the task is done, waits on the user, fails or is stopped; settles the task
     * through {@link HarnessRun}. {@link HarnessRun#reply()} is what the user sent, when they answered a wait.
     */
    void drive(HarnessRun run);

    /** Cleans up what a deleted task left behind (a worktree, a clone). */
    default void discard(HarnessState state) {
    }

    /**
     * Before a task of this kind is created: an {@link IllegalArgumentException} for anything that would only stop it
     * later (a path, a branch, a missing authorization), so the user fixes it in the chat instead.
     */
    default void precheck(String repo, String branch, String delivery, String owner, HarnessHost host) {
    }
}
