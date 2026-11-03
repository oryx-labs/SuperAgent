package com.superagent.storage.jdbc;

import com.superagent.core.connector.ConnectorCredential;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse credential store: {@code ReplacingMergeTree(updated_at)} keyed by
 * connector_id, so save re-inserts a newer version and reads use {@code FINAL};
 * delete is an {@code ALTER TABLE ... DELETE} mutation. Apply
 * {@code db/clickhouse/schema.sql} first.
 */
public class ClickHouseConnectorCredentialStore extends AbstractJdbcConnectorCredentialStore {

    public ClickHouseConnectorCredentialStore(JdbcTemplate jdbc) {
        super(jdbc, List.of());
    }

    @Override
    protected String credentialFinal() {
        return " FINAL";
    }

    @Override
    public ConnectorCredential save(ConnectorCredential c) {
        insert(c);
        return c;
    }

    @Override
    public void delete(String connectorId) {
        jdbc.update("ALTER TABLE connector_credential DELETE WHERE connector_id = ?", connectorId);
    }
}
