package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse organisation store: self-creating tables; replacing a user's rows is a synchronous
 * {@code ALTER ... DELETE} then an insert, so the next read sees the new rows only.
 */
public class ClickHouseOrgStore extends AbstractJdbcOrgStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS user_manager (
              username   String,
              manager    String,
              updated_at DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY username
            """,
            """
            CREATE TABLE IF NOT EXISTS user_team (
              username  String,
              team_path String
            ) ENGINE = MergeTree
            ORDER BY (username, team_path)
            """);

    public ClickHouseOrgStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String managerFinal() {
        return " FINAL";
    }

    @Override
    protected void deleteWhere(String table, String where, Object... args) {
        jdbc.update("ALTER TABLE " + table + " DELETE WHERE " + where + " SETTINGS mutations_sync = 1", args);
    }
}
