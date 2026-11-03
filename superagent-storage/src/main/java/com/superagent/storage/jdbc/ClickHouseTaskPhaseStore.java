package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse task-phase store: {@code ReplacingMergeTree(updated_at)} keyed by
 * task_id, so save re-inserts a newer version and reads use {@code FINAL}; delete
 * is an {@code ALTER TABLE ... DELETE} mutation. Apply {@code db/clickhouse/schema.sql} first.
 */
public class ClickHouseTaskPhaseStore extends AbstractJdbcTaskPhaseStore {

    public ClickHouseTaskPhaseStore(JdbcTemplate jdbc) {
        super(jdbc, List.of());
    }

    @Override
    protected String phaseFinal() {
        return " FINAL";
    }

    @Override
    public void save(String taskId, String phasesJson) {
        jdbc.update("INSERT INTO task_phase (task_id, phases_json, updated_at) VALUES (?, ?, ?)",
                taskId, phasesJson, nowUtc());
    }

    @Override
    public void delete(String taskId) {
        jdbc.update("ALTER TABLE task_phase DELETE WHERE task_id = ?", taskId);
    }
}
