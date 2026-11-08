package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL task-phase store: upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlTaskPhaseStore extends AbstractJdbcTaskPhaseStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS task_phase (
              company_id  VARCHAR(64) NOT NULL,
              task_id     VARCHAR(64) NOT NULL,
              phases_json MEDIUMTEXT  NOT NULL,
              updated_at  DATETIME(3) NOT NULL,
              PRIMARY KEY (company_id, task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlTaskPhaseStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String phaseFinal() {
        return "";
    }

    @Override
    public void save(String taskId, String phasesJson) {
        jdbc.update("INSERT INTO task_phase (company_id, task_id, phases_json, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE phases_json=VALUES(phases_json), updated_at=VALUES(updated_at)",
                TenantSchema.company(), taskId, phasesJson, nowUtc());
    }

    @Override
    public void delete(String taskId) {
        jdbc.update("DELETE FROM task_phase WHERE company_id = ? AND task_id = ?", TenantSchema.company(), taskId);
    }
}
