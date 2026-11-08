package com.superagent.storage.jdbc;

import com.superagent.core.connector.ConnectorCredential;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse credential store: {@code ReplacingMergeTree(updated_at)} keyed by
 * (company_id, connector_id, owner), so save re-inserts a newer version and reads use {@code FINAL};
 * delete is an {@code ALTER TABLE ... DELETE} mutation.
 */
public class ClickHouseConnectorCredentialStore extends AbstractJdbcConnectorCredentialStore {

    public ClickHouseConnectorCredentialStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS connector_credential (
                  company_id String,
                  connector_id String,
                  owner String,
                  secret_cipher String,
                  account_label String,
                  updated_at DateTime64(3, 'UTC'),
                  refresh_cipher String,
                  expires_at Nullable(DateTime64(3, 'UTC'))
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, connector_id, owner)
                """));
    }

    @Override
    protected String credentialFinal() {
        return " FINAL";
    }

    @Override
    public ConnectorCredential save(ConnectorCredential c) {
        jdbc.update("INSERT INTO connector_credential " + INSERT, params(c));
        return c;
    }

    @Override
    public void delete(String connectorId, String owner) {
        jdbc.update("ALTER TABLE connector_credential DELETE WHERE company_id = ? AND connector_id = ? AND owner = ? "
                + "SETTINGS mutations_sync = 1", TenantSchema.company(), connectorId, owner);
    }

    @Override
    public void deleteAll(String connectorId) {
        jdbc.update("ALTER TABLE connector_credential DELETE WHERE company_id = ? AND connector_id = ? "
                + "SETTINGS mutations_sync = 1", TenantSchema.company(), connectorId);
    }
}
