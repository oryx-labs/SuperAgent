package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse work files: {@code ReplacingMergeTree}; writes are inserts, reads use FINAL. */
public class ClickHouseWorkFileStore extends AbstractJdbcWorkFileStore {

    public ClickHouseWorkFileStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS work_file (
                  company_id String, id String, name String, kind String, mime_type String, size Int64, storage String, location String,
                  owner String, agent_id String, task_id String, origin String, deleted UInt8,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC'), tags String, tag_state String
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """, "ALTER TABLE work_file ADD COLUMN IF NOT EXISTS tags String",
                "ALTER TABLE work_file ADD COLUMN IF NOT EXISTS tag_state String", """
                CREATE TABLE IF NOT EXISTS work_file_ref (
                  company_id String, file_id String, session_id String, message_id String, created_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(created_at)
                ORDER BY (company_id, file_id, message_id)
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
