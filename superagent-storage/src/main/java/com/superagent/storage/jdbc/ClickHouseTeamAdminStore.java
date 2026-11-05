package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/** ClickHouse team admins: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseTeamAdminStore extends AbstractJdbcTeamAdminStore {

    public ClickHouseTeamAdminStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS team_admin (
                  team_path String, username String, active UInt8, updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (team_path, username)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    protected void upsert(String team, String username, boolean active, LocalDateTime at) {
        jdbc.update("INSERT INTO team_admin (team_path, username, active, updated_at) VALUES (?, ?, ?, ?)",
                team, username, active ? 1 : 0, at);
    }
}
