package com.superagent.core.task;

import java.util.Optional;

/** Persistence SPI for durable per-task run-state (session, failure/attempt counters). */
public interface TaskRunStateStore {

    Optional<TaskRunState> find(String taskId);

    /** Inserts or replaces the run-state by task id. */
    TaskRunState save(TaskRunState state);

    void delete(String taskId);
}
