package com.superagent.storage.jdbc;

import com.superagent.core.user.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL user store: update-or-insert, hard delete. */
public class MysqlUserStore extends AbstractJdbcUserStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS app_user (
              company_id    VARCHAR(64)  NOT NULL,
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
              must_change_password TINYINT(1) NOT NULL DEFAULT 0,
              PRIMARY KEY (company_id, id),
              UNIQUE KEY uq_user_username (company_id, username),
              KEY idx_user_phone (company_id, phone),
              KEY idx_user_external (company_id, external_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlUserStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String userFinal() {
        return "";
    }

    // Update by (company, id), else insert: a username taken in the company then fails on the unique key instead of
    // overwriting that other user.
    @Override
    public User save(User u) {
        int updated = jdbc.update("UPDATE app_user SET username = ?, display_name = ?, roles = ?, enabled = ?, "
                        + "password_hash = ?, phone = ?, external_id = ?, source = ?, updated_at = ?, must_change_password = ? "
                        + "WHERE company_id = ? AND id = ?",
                u.username(), nn(u.displayName()), String.join(",", u.roles()), u.enabled(), nn(u.passwordHash()),
                nn(u.phone()), nn(u.externalId()), u.source().name(), utc(u.updatedAt()), u.mustChangePassword(), TenantSchema.company(), u.id());
        if (updated == 0) {
            insert(u);
        }
        return u;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM app_user WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
