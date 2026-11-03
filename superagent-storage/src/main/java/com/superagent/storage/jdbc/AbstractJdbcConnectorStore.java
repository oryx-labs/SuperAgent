package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorAuthMethod;
import com.superagent.core.connector.ConnectorStatus;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.ConnectorType;
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
 * JDBC {@link ConnectorStore} shared by MySQL and ClickHouse. Timestamps are
 * stored as UTC {@link LocalDateTime} so they round-trip across drivers. Backends
 * differ only in {@link #save} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcConnectorStore implements ConnectorStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcConnectorStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String connectorFinal();

    private final RowMapper<Connector> mapper = (rs, n) -> new Connector(
            rs.getString("id"),
            rs.getString("name"),
            emptyToNull(rs.getString("description")),
            ConnectorType.valueOf(rs.getString("type")),
            ConnectorAuthMethod.valueOf(rs.getString("auth_method")),
            ConnectorStatus.valueOf(rs.getString("status")),
            ConnectorTransport.valueOf(rs.getString("transport")),
            emptyToNull(rs.getString("endpoint")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    @Override
    public List<Connector> listConnectors() {
        return jdbc.query("SELECT * FROM connector" + connectorFinal() + " ORDER BY created_at", mapper);
    }

    @Override
    public Optional<Connector> findConnector(String id) {
        return jdbc.query("SELECT * FROM connector" + connectorFinal() + " WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    protected void insert(Connector c) {
        jdbc.update("INSERT INTO connector "
                        + "(id, name, description, type, auth_method, status, transport, endpoint, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.name(), nn(c.description()), c.type().name(), c.authMethod().name(),
                c.status().name(), c.transport().name(), nn(c.endpoint()), utc(c.createdAt()), utc(c.updatedAt()));
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
