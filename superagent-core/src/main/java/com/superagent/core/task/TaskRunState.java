package com.superagent.core.task;

import java.time.Instant;

/**
 * Durable run-state for a task, kept in its own table so the task record stays
 * clean. It survives restarts, which is what lets a watchdog resume an interrupted
 * run: {@code agentSessionId} is the agent conversation to {@code --resume};
 * {@code failureCount} counts consecutive failures on the current session (reset
 * when the session is rotated); {@code attemptCount} is the total number of runs
 * (the overall retry budget). {@code interruptedByRestart} marks a run a server
 * shutdown cut short: it resumes right after startup without spending the budget.
 */
public record TaskRunState(
        String taskId,
        String agentSessionId,
        int failureCount,
        int attemptCount,
        Instant updatedAt,
        boolean interruptedByRestart) {

    public TaskRunState(String taskId, String agentSessionId, int failureCount, int attemptCount, Instant updatedAt) {
        this(taskId, agentSessionId, failureCount, attemptCount, updatedAt, false);
    }

    public static TaskRunState initial(String taskId, String agentSessionId) {
        return new TaskRunState(taskId, agentSessionId, 0, 1, Instant.now());
    }

    /** Next run on the same session after a failure: bump both counters. */
    public TaskRunState failedOnSameSession() {
        return new TaskRunState(taskId, agentSessionId, failureCount + 1, attemptCount + 1, Instant.now());
    }

    /** Rotated onto a fresh session: reset the per-session failure counter, keep the total. */
    public TaskRunState rotatedTo(String newSessionId) {
        return new TaskRunState(taskId, newSessionId, 0, attemptCount + 1, Instant.now());
    }

    /** A plain retry/resume on the same session without counting it as a failure. */
    public TaskRunState resumed() {
        return new TaskRunState(taskId, agentSessionId, failureCount, attemptCount + 1, Instant.now());
    }

    /** The run was cut short by a server shutdown, on {@code sessionId} (the conversation to pick up). */
    public TaskRunState interruptedByRestart(String sessionId) {
        return new TaskRunState(taskId, sessionId != null ? sessionId : agentSessionId, failureCount, attemptCount,
                Instant.now(), true);
    }

    /** Picked up after the restart: same session, nothing counted, the mark cleared. */
    public TaskRunState resumedAfterRestart() {
        return new TaskRunState(taskId, agentSessionId, failureCount, attemptCount, Instant.now(), false);
    }
}
