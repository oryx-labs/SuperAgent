package com.superagent.core.task;

import java.util.Optional;

/**
 * Persistence SPI for a task's phase array, stored as one row per task. Core keeps
 * the payload as an opaque JSON string so it stays Jackson-free; the web layer
 * serializes/deserializes the {@link TaskPhase} list. The active backend is chosen
 * in superagent-storage.
 */
public interface TaskPhaseStore {

    Optional<String> findPhasesJson(String taskId);

    /** Inserts or replaces the task's phase JSON. */
    void save(String taskId, String phasesJson);

    void delete(String taskId);
}
