package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentWork;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse SuperAgent work store: self-creating tables. Work is a {@code ReplacingMergeTree(updated_at)}
 * keyed by task id (save re-inserts, reads use {@code FINAL}); events are an append-only {@code MergeTree}.
 */
public class ClickHouseSuperAgentWorkStore extends AbstractJdbcSuperAgentWorkStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS superagent_work (
              task_id     String,
              owner       String,
              plan_id     String,
              title       String,
              member_id   String,
              member_name String,
              status      String,
              summary     String,
              assigned_at DateTime64(3, 'UTC'),
              finished_at Nullable(DateTime64(3, 'UTC')),
              updated_at  DateTime64(3, 'UTC'),
              message_id  String
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY task_id
            """,
            """
            CREATE TABLE IF NOT EXISTS superagent_work_event (
              id      String,
              task_id String,
              owner   String,
              kind    String,
              detail  String,
              at_time DateTime64(3, 'UTC')
            ) ENGINE = MergeTree
            ORDER BY (task_id, at_time)
            """);

    public ClickHouseSuperAgentWorkStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        jdbc.execute("ALTER TABLE superagent_work ADD COLUMN IF NOT EXISTS message_id String");
    }

    @Override
    protected String workFinal() {
        return " FINAL";
    }

    @Override
    public SuperAgentWork save(SuperAgentWork w) {
        jdbc.update("INSERT INTO superagent_work " + COLUMNS + VALUES, params(w));
        return w;
    }
}
