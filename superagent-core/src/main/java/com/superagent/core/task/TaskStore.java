package com.superagent.core.task;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for task metadata. The active backend is chosen in superagent-storage. */
public interface TaskStore {

    List<Task> listTasks();

    Optional<Task> findTask(String id);

    /** Inserts or replaces the task by id. */
    Task save(Task task);

    void delete(String id);
}
