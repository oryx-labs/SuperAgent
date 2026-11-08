package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse connector store: {@code ReplacingMergeTree(updated_at)} keyed by (company_id, id),
 * so save re-inserts a newer version and reads use {@code FINAL}; delete is an
 * {@code ALTER TABLE ... DELETE} mutation.
 */
public class ClickHouseConnectorStore extends AbstractJdbcConnectorStore {

    public ClickHouseConnectorStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS connector (
                  company_id String,
                  id String,
                  name String,
                  description String,
                  kind String,
                  auth_method String,
                  enabled UInt8,
                  transport String,
                  endpoint String,
                  token_env String,
                  created_at DateTime64(3, 'UTC'),
                  updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """));
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
        jdbc.update("ALTER TABLE connector DELETE WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
