package com.superagent.storage.jdbc;

import com.superagent.core.task.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse task store: {@code ReplacingMergeTree(updated_at)} keyed by id, so
 * save re-inserts a newer version and reads use {@code FINAL}; delete is an
 * {@code ALTER TABLE ... DELETE} mutation. Apply {@code db/clickhouse/schema.sql} first.
 */
public class ClickHouseTaskStore extends AbstractJdbcTaskStore {

    public ClickHouseTaskStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("ALTER TABLE task ADD COLUMN IF NOT EXISTS tier String",
                // Cents were too coarse: a small task costs a fraction of a cent.
                "ALTER TABLE task MODIFY COLUMN spend Decimal(16, 6)"));
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
        jdbc.update("ALTER TABLE task DELETE WHERE id = ?", id);
    }
}
