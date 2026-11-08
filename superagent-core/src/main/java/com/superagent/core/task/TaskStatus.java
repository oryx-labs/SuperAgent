package com.superagent.core.task;

/**
 * Lifecycle of a task run.
 *
 * <pre>
 *   PENDING ──▶ RUNNING ──┬─▶ DONE
 *                 ▲        ├─▶ ERROR
 *                 │        └─▶ STOPPED
 *        NEEDS_CONFIRMATION ◀┘ (model paused for user input; resumes to RUNNING)
 *
 *   A complex task, after planning: RUNNING ──▶ WAITING (its subtasks run) ──▶ RUNNING (it sums them up) ──▶ DONE
 * </pre>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    NEEDS_CONFIRMATION,
    /** A complex task waiting for its subtasks; it carries on by itself once they have all finished. */
    WAITING,
    DONE,
    ERROR,
    STOPPED,
    DELETED
}
