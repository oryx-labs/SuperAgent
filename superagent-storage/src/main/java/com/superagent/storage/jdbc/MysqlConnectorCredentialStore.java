package com.superagent.storage.jdbc;

import com.superagent.core.connector.ConnectorCredential;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL credential store: upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlConnectorCredentialStore extends AbstractJdbcConnectorCredentialStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS connector_credential (
              connector_id  VARCHAR(64)   NOT NULL,
              access_token  VARCHAR(2048) NOT NULL,
              account_label VARCHAR(255),
              updated_at    DATETIME(3)   NOT NULL,
              PRIMARY KEY (connector_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlConnectorCredentialStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String credentialFinal() {
        return "";
    }

    @Override
    public ConnectorCredential save(ConnectorCredential c) {
        jdbc.update("INSERT INTO connector_credential (connector_id, access_token, account_label, updated_at) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE access_token=VALUES(access_token), "
                        + "account_label=VALUES(account_label), updated_at=VALUES(updated_at)",
                c.connectorId(), nn(c.accessToken()), nn(c.accountLabel()), utc(c.updatedAt()));
        return c;
    }

    @Override
    public void delete(String connectorId) {
        jdbc.update("DELETE FROM connector_credential WHERE connector_id = ?", connectorId);
    }
}
