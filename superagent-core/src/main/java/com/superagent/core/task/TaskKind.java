package com.superagent.core.task;

/**
 * The kind of work a task is, which picks how it runs. {@link #CODING} goes through a fixed flow (PRD → develop →
 * verify, each with its own gate); {@link #GENERAL} runs in one pass. A new scenario with steps of its own is a new
 * constant here plus its flow next to the coding one in superagent-task.
 *
 * <p>Whoever creates the task says which kind it is (the agent or SuperAgent knows what it's asking for); a task
 * that names a repository is coding, and anything else is general. Kept as the lowercase name in the task's
 * {@code flow} column, so {@link #storageName()} / {@link #fromStorage(String)} are the only conversion points.
 */
public enum TaskKind {
    CODING, GENERAL;

    public String storageName() {
        return name().toLowerCase();
    }

    /** Null, blank and unknown (including kinds since retired, such as writing or research) read as {@link #GENERAL}. */
    public static TaskKind fromStorage(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GENERAL;
        }
    }
}
