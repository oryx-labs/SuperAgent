package com.superagent.storage.jdbc;

import com.superagent.core.user.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse user store: {@code ReplacingMergeTree(updated_at)} keyed by (company_id, id), so
 * save re-inserts a newer version and reads use {@code FINAL}; delete is an
 * {@code ALTER TABLE ... DELETE} mutation.
 */
public class ClickHouseUserStore extends AbstractJdbcUserStore {

    public ClickHouseUserStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS app_user (
                  company_id String,
                  id String,
                  username String,
                  display_name String,
                  roles String,
                  enabled UInt8,
                  password_hash String,
                  phone String,
                  external_id String,
                  source String,
                  created_at DateTime64(3, 'UTC'),
                  updated_at DateTime64(3, 'UTC'),
                  must_change_password UInt8 DEFAULT 0
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """));
    }

    @Override
    protected String userFinal() {
        return " FINAL";
    }

    @Override
    public User save(User u) {
        insert(u);
        return u;
    }

    @Override
    public void delete(String id) {
        jdbc.update("ALTER TABLE app_user DELETE WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
