package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse work files: {@code ReplacingMergeTree}; writes are inserts, reads use FINAL. */
public class ClickHouseWorkFileStore extends AbstractJdbcWorkFileStore {

    public ClickHouseWorkFileStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS work_file (
                  id String, name String, kind String, mime_type String, size Int64, storage String, location String,
                  owner String, agent_id String, task_id String, origin String, deleted UInt8,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (id)
                """, """
                CREATE TABLE IF NOT EXISTS work_file_ref (
                  file_id String, session_id String, message_id String, created_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(created_at)
                ORDER BY (file_id, message_id)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    protected String upsert() {
        return "INSERT INTO";
    }
}
