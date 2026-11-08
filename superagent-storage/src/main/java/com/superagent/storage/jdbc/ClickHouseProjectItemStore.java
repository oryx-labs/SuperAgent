package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse project items: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseProjectItemStore extends AbstractJdbcProjectItemStore {

    public ClickHouseProjectItemStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS project_item (
                  company_id String, kind String, item_id String, project_id String, updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, kind, item_id)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    protected String upsert() {
        return "INSERT";
    }
}
