package com.superagent.storage.jdbc;

import com.superagent.core.connector.Connector;
import com.superagent.core.connector.ConnectorAuthMethod;
import com.superagent.core.connector.ConnectorKind;
import com.superagent.core.connector.ConnectorStore;
import com.superagent.core.connector.ConnectorTransport;
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

    protected static final String COLUMNS = "(id, name, description, kind, auth_method, enabled, transport, endpoint, "
            + "token_env, created_at, updated_at)";
    protected static final String VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
            ConnectorKind.valueOf(rs.getString("kind")),
            ConnectorAuthMethod.valueOf(rs.getString("auth_method")),
            rs.getInt("enabled") != 0,
            ConnectorTransport.valueOf(rs.getString("transport")),
            emptyToNull(rs.getString("endpoint")),
            emptyToNull(rs.getString("token_env")),
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

    protected Object[] params(Connector c) {
        return new Object[]{c.id(), c.name(), nn(c.description()), c.kind().name(), c.authMethod().name(),
                c.enabled() ? 1 : 0, c.transport().name(), nn(c.endpoint()), nn(c.tokenEnv()),
                utc(c.createdAt()), utc(c.updatedAt())};
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

    /**
     * Connectors and their credentials changed shape (kinds, per-employee secrets) with no migration: an
     * older table is dropped so the new one can be created.
     */
    static void dropIfOld(JdbcTemplate jdbc, String table, String newColumn) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = ?", Integer.class, table);
        Integer current = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = ? AND LOWER(column_name) = ?", Integer.class, table, newColumn);
        if (exists != null && exists > 0 && (current == null || current == 0)) {
            jdbc.execute("DROP TABLE " + table);
        }
    }
}
