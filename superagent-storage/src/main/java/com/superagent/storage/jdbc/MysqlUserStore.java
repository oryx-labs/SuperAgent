package com.superagent.storage.jdbc;

import com.superagent.core.user.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL user store: upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlUserStore extends AbstractJdbcUserStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS app_user (
              id            VARCHAR(64)  NOT NULL,
              username      VARCHAR(190) NOT NULL,
              display_name  VARCHAR(255),
              roles         VARCHAR(512),
              enabled       TINYINT(1)   NOT NULL DEFAULT 1,
              password_hash VARCHAR(255),
              phone         VARCHAR(32),
              external_id   VARCHAR(255),
              source        VARCHAR(16)  NOT NULL,
              created_at    DATETIME(3)  NOT NULL,
              updated_at    DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uq_user_username (username),
              KEY idx_user_phone (phone),
              KEY idx_user_external (external_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlUserStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String userFinal() {
        return "";
    }

    @Override
    public User save(User u) {
        jdbc.update("INSERT INTO app_user " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE username=VALUES(username), display_name=VALUES(display_name), "
                        + "roles=VALUES(roles), enabled=VALUES(enabled), password_hash=VALUES(password_hash), "
                        + "phone=VALUES(phone), external_id=VALUES(external_id), source=VALUES(source), "
                        + "updated_at=VALUES(updated_at)",
                params(u));
        return u;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
}
