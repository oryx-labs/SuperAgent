package com.superagent.core.task;

/**
 * Lifecycle of a task run.
 *
 * <pre>
 *   PENDING ──▶ RUNNING ──┬─▶ DONE
 *                 ▲        ├─▶ ERROR
 *                 │        └─▶ STOPPED
 *        NEEDS_CONFIRMATION ◀┘ (model paused for user input; resumes to RUNNING)
 * </pre>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    NEEDS_CONFIRMATION,
    DONE,
    ERROR,
    STOPPED,
    DELETED
}
