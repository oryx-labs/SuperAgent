package com.superagent.core.task;

/**
 * The kind of work a task is, which selects how it is executed. Only {@link #CODING}
 * has a fixed multi-phase flow today (PRD → develop → verify); the others are
 * placeholders that run the single-pass flow unchanged.
 *
 * <p>Persisted as its lowercase name in the task's {@code flow} column (zero migration,
 * the column already exists), so {@link #storageName()} / {@link #fromStorage(String)}
 * are the only conversion points.
 */
public enum TaskKind {
    CODING, WRITING, RESEARCH, STATS, GENERAL;

    public String storageName() {
        return name().toLowerCase();
    }

    /** Null/blank/unknown all fall back to {@link #GENERAL}, so legacy rows stay valid. */
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
