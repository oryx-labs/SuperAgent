package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse connector store: {@code ReplacingMergeTree(updated_at)} keyed by id,
 * so save re-inserts a newer version and reads use {@code FINAL}; delete is an
 * {@code ALTER TABLE ... DELETE} mutation. Apply {@code db/clickhouse/schema.sql} first.
 */
public class ClickHouseConnectorStore extends AbstractJdbcConnectorStore {

    public ClickHouseConnectorStore(JdbcTemplate jdbc) {
        super(jdbc, List.of());
    }

    @Override
    protected String connectorFinal() {
        return " FINAL";
    }

    @Override
    public Connector save(Connector c) {
        jdbc.update("INSERT INTO connector " + COLUMNS + " VALUES " + VALUES, params(c));
        return c;
    }

    @Override
    public void delete(String id) {
        jdbc.update("ALTER TABLE connector DELETE WHERE id = ?", id);
    }
}
