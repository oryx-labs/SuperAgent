package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskRunState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse run-state store: self-creating {@code ReplacingMergeTree(updated_at)}
 * table keyed by (company_id, task_id), so save re-inserts a newer version and reads use
 * {@code FINAL}; delete is an {@code ALTER TABLE ... DELETE} mutation.
 */
public class ClickHouseTaskRunStateStore extends AbstractJdbcTaskRunStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS task_run_state (
              company_id       String,
              task_id          String,
              agent_session_id String,
              failure_count    Int32,
              attempt_count    Int32,
              updated_at       DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (company_id, task_id)
            """);

    public ClickHouseTaskRunStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String stateFinal() {
        return " FINAL";
    }

    @Override
    public TaskRunState save(TaskRunState s) {
        insert(s);
        return s;
    }

    @Override
    public void delete(String taskId) {
        jdbc.update("ALTER TABLE task_run_state DELETE WHERE company_id = ? AND task_id = ?", TenantSchema.company(), taskId);
    }
}
