package com.superagent.storage.jdbc;

import com.superagent.core.connector.ConnectorCredential;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL credential store: upsert via {@code REPLACE}, hard delete. */
public class MysqlConnectorCredentialStore extends AbstractJdbcConnectorCredentialStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS connector_credential (
              connector_id  VARCHAR(64)   NOT NULL,
              owner         VARCHAR(128)  NOT NULL,
              secret_cipher VARCHAR(4096) NOT NULL,
              account_label VARCHAR(255),
              updated_at    DATETIME(3)   NOT NULL,
              refresh_cipher VARCHAR(4096),
              expires_at    DATETIME(3),
              PRIMARY KEY (connector_id, owner)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlConnectorCredentialStore(JdbcTemplate jdbc) {
        super(dropOld(jdbc), SCHEMA);
        // Tables from before expiring tokens (Gitee): add the columns.
        for (String[] col : new String[][]{{"refresh_cipher", "VARCHAR(4096)"}, {"expires_at", "DATETIME(3)"}}) {
            Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                    + "AND LOWER(table_name) = 'connector_credential' AND LOWER(column_name) = ?", Integer.class, col[0]);
            if (present == null || present == 0) {
                jdbc.execute("ALTER TABLE connector_credential ADD COLUMN " + col[0] + " " + col[1]);
            }
        }
    }

    private static JdbcTemplate dropOld(JdbcTemplate jdbc) {
        AbstractJdbcConnectorStore.dropIfOld(jdbc, "connector_credential", "owner");
        return jdbc;
    }

    @Override
    protected String credentialFinal() {
        return "";
    }

    @Override
    public ConnectorCredential save(ConnectorCredential c) {
        jdbc.update("REPLACE INTO connector_credential " + INSERT, params(c));
        return c;
    }

    @Override
    public void delete(String connectorId, String owner) {
        jdbc.update("DELETE FROM connector_credential WHERE connector_id = ? AND owner = ?", connectorId, owner);
    }

    @Override
    public void deleteAll(String connectorId) {
        jdbc.update("DELETE FROM connector_credential WHERE connector_id = ?", connectorId);
    }
}
