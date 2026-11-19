package com.superagent.core.task;

import java.time.Instant;
import java.util.List;

/** What tasks wait for before they start. */
public interface TaskDependencyStore {

    void save(TaskDependency dependency);

    /** What this task waits for, oldest first. */
    List<TaskDependency> of(String taskId);

    /** Every dependency in the company, for the scheduler's pass. */
    List<TaskDependency> all();

    void markNotified(String taskId, String dependsOn, Instant at);

    /** Drops everything this task waits for: it may start now ("run it anyway"). */
    void clear(String taskId);
}
