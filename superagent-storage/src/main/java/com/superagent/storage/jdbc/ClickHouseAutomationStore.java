package com.superagent.storage.jdbc;

import com.superagent.core.automation.Automation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse automation store: self-creating tables. Automations are a
 * {@code ReplacingMergeTree(updated_at)} keyed by id (save re-inserts, reads use {@code FINAL},
 * delete is an {@code ALTER ... DELETE}); runs are a plain append-only {@code MergeTree}.
 */
public class ClickHouseAutomationStore extends AbstractJdbcAutomationStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS automation (
              id             String,
              name           String,
              prompt         String,
              type           String,
              cron           String,
              run_at         Nullable(DateTime64(3, 'UTC')),
              timezone       String,
              model          String,
              overlap_policy String,
              enabled        UInt8,
              source         String,
              owner          String,
              api_token_hash String,
              next_run_at    Nullable(DateTime64(3, 'UTC')),
              last_run_at    Nullable(DateTime64(3, 'UTC')),
              last_task_id   String,
              created_at     DateTime64(3, 'UTC'),
              updated_at     DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY id
            """,
            "ALTER TABLE automation ADD COLUMN IF NOT EXISTS tier String",
            """
            CREATE TABLE IF NOT EXISTS automation_run (
              id             String,
              automation_id  String,
              trigger_kind   String,
              task_id        String,
              skipped_reason String,
              fired_at       DateTime64(3, 'UTC')
            ) ENGINE = MergeTree
            ORDER BY (automation_id, fired_at)
            """);

    public ClickHouseAutomationStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String automationFinal() {
        return " FINAL";
    }

    @Override
    public Automation save(Automation a) {
        jdbc.update("INSERT INTO automation " + COLUMNS + VALUES, params(a));
        return a;
    }

    @Override
    public void delete(String id) {
        jdbc.update("ALTER TABLE automation DELETE WHERE id = ?", id);
    }
}
