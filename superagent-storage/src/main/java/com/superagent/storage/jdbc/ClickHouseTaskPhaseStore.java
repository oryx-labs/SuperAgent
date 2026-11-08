package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse task-phase store: {@code ReplacingMergeTree(updated_at)} keyed by
 * (company_id, task_id), so save re-inserts a newer version and reads use {@code FINAL}; delete
 * is an {@code ALTER TABLE ... DELETE} mutation.
 */
public class ClickHouseTaskPhaseStore extends AbstractJdbcTaskPhaseStore {

    public ClickHouseTaskPhaseStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS task_phase (
                  company_id String,
                  task_id String,
                  phases_json String,
                  updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, task_id)
                """));
    }

    @Override
    protected String phaseFinal() {
        return " FINAL";
    }

    @Override
    public void save(String taskId, String phasesJson) {
        jdbc.update("INSERT INTO task_phase (company_id, task_id, phases_json, updated_at) VALUES (?, ?, ?, ?)",
                TenantSchema.company(), taskId, phasesJson, nowUtc());
    }

    @Override
    public void delete(String taskId) {
        jdbc.update("ALTER TABLE task_phase DELETE WHERE company_id = ? AND task_id = ?", TenantSchema.company(), taskId);
    }
}
