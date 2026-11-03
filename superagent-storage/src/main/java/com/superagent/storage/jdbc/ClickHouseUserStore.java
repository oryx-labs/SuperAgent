package com.superagent.storage.jdbc;

import com.superagent.core.user.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse user store: {@code ReplacingMergeTree(updated_at)} keyed by id, so
 * save re-inserts a newer version and reads use {@code FINAL}; delete is an
 * {@code ALTER TABLE ... DELETE} mutation. Apply {@code db/clickhouse/schema.sql} first.
 */
public class ClickHouseUserStore extends AbstractJdbcUserStore {

    public ClickHouseUserStore(JdbcTemplate jdbc) {
        super(jdbc, List.of());
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
        jdbc.update("ALTER TABLE app_user DELETE WHERE id = ?", id);
    }
}
