package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL organisation store: self-creating tables, plain DELETE / INSERT. */
public class MysqlOrgStore extends AbstractJdbcOrgStore {

    // InnoDB caps a key at 3072 bytes = 768 utf8mb4 chars: company_id (64) + username (190, as in the user table)
    // + team_path (514 ≥ 8 levels × 64 chars + separators) fits exactly.
    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS user_manager (
              company_id VARCHAR(64)  NOT NULL,
              username   VARCHAR(190) NOT NULL,
              manager    VARCHAR(190) NOT NULL,
              updated_at DATETIME(3)  NOT NULL,
              PRIMARY KEY (company_id, username)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS user_team (
              company_id VARCHAR(64)  NOT NULL,
              username   VARCHAR(190) NOT NULL,
              team_path  VARCHAR(514) NOT NULL,
              PRIMARY KEY (company_id, username, team_path),
              KEY idx_user_team_path (company_id, team_path)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlOrgStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String managerFinal() {
        return "";
    }

    @Override
    protected void deleteWhere(String table, String where, Object... args) {
        jdbc.update("DELETE FROM " + table + " WHERE " + where, args);
    }
}
