package com.superagent.storage.jdbc;

import com.superagent.core.task.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse task store: {@code ReplacingMergeTree(updated_at)} keyed by (company_id, id), so
 * save re-inserts a newer version and reads use {@code FINAL}; delete is an
 * {@code ALTER TABLE ... DELETE} mutation.
 */
public class ClickHouseTaskStore extends AbstractJdbcTaskStore {

    public ClickHouseTaskStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS task (
                  company_id String,
                  id String,
                  title String,
                  brief String,
                  source String,
                  repo String,
                  branch String,
                  flow String,
                  status String,
                  progress Int32,
                  spend Decimal(16, 6),
                  model String,
                  tier String,
                  agent_type String,
                  workspace_dir String,
                  created_by String,
                  error String,
                  created_at DateTime64(3, 'UTC'),
                  started_at Nullable(DateTime64(3, 'UTC')),
                  finished_at Nullable(DateTime64(3, 'UTC')),
                  updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """));
    }

    @Override
    protected String taskFinal() {
        return " FINAL";
    }

    @Override
    public Task save(Task t) {
        insert(t);
        return t;
    }

    @Override
    public void delete(String id) {
        jdbc.update("ALTER TABLE task DELETE WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
