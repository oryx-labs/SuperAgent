package com.superagent.storage.jdbc;

import com.superagent.core.connector.ConnectorCredential;
import com.superagent.core.connector.ConnectorCredentialStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link ConnectorCredentialStore} shared by MySQL and ClickHouse, mirroring
 * {@link AbstractJdbcConnectorStore}. Backends differ only in writes and whether
 * reads need {@code FINAL}.
 */
abstract class AbstractJdbcConnectorCredentialStore implements ConnectorCredentialStore {

    protected static final String INSERT = "(connector_id, owner, secret_cipher, account_label, updated_at) VALUES (?, ?, ?, ?, ?)";

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcConnectorCredentialStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String credentialFinal();

    private final RowMapper<ConnectorCredential> mapper = (rs, n) -> new ConnectorCredential(
            rs.getString("connector_id"),
            rs.getString("owner"),
            emptyToNull(rs.getString("secret_cipher")),
            emptyToNull(rs.getString("account_label")),
            instant(rs, "updated_at"));

    @Override
    public Optional<ConnectorCredential> find(String connectorId, String owner) {
        return jdbc.query("SELECT * FROM connector_credential" + credentialFinal() + " WHERE connector_id = ? AND owner = ?",
                mapper, connectorId, owner).stream().findFirst();
    }

    @Override
    public List<ConnectorCredential> list(String connectorId) {
        return jdbc.query("SELECT * FROM connector_credential" + credentialFinal() + " WHERE connector_id = ?",
                mapper, connectorId);
    }

    protected Object[] params(ConnectorCredential c) {
        return new Object[]{c.connectorId(), c.owner(), nn(c.secretCipher()), nn(c.accountLabel()), utc(c.updatedAt())};
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    protected static String nn(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
