package com.superagent.core.task;

/** Lifecycle of a single task phase. The first non-{@link #DONE} phase is the current one. */
public enum TaskPhaseStatus {
    PENDING, RUNNING, DONE
}
