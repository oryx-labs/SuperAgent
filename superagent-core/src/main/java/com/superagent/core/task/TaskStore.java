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

    /**
     * Gives a newly created task its company's next number (1, 2, 3…), the short id people quote. Kept for good: a
     * deleted task's number is never handed out again.
     */
    default void assignNumber(String id) {
    }

    /** Every task's number in the company, by task id (a task created before numbering has one too). */
    default java.util.Map<String, Long> numbers() {
        return java.util.Map.of();
    }

}
