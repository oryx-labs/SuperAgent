package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/** MySQL team admins: self-creating table, upsert via REPLACE. Key 520+190 chars fits the 3072-byte limit. */
public class MysqlTeamAdminStore extends AbstractJdbcTeamAdminStore {

    public MysqlTeamAdminStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS team_admin (
                  team_path  VARCHAR(520) NOT NULL,
                  username   VARCHAR(190) NOT NULL,
                  active     TINYINT      NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (team_path, username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    protected void upsert(String team, String username, boolean active, LocalDateTime at) {
        jdbc.update("REPLACE INTO team_admin (team_path, username, active, updated_at) VALUES (?, ?, ?, ?)",
                team, username, active ? 1 : 0, at);
    }
}
