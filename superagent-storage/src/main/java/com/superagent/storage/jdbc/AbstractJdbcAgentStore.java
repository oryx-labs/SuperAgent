package com.superagent.storage.jdbc;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
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
 * JDBC {@link AgentStore} shared by MySQL and ClickHouse, mirroring
 * {@link AbstractJdbcTaskStore}. Timestamps are stored as UTC {@link LocalDateTime}.
 * Backends differ only in {@link #update} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcAgentStore implements AgentStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcAgentStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String agentFinal();

    private final RowMapper<Agent> mapper = (rs, n) -> new Agent(
            rs.getString("id"),
            rs.getString("name"),
            emptyToNull(rs.getString("avatar")),
            emptyToNull(rs.getString("persona")),
            emptyToNull(rs.getString("model")),
            AgentStatus.valueOf(rs.getString("status")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    protected static final String COLUMNS = "(id, name, avatar, persona, model, status, created_at, updated_at)";

    protected Object[] params(Agent a) {
        return new Object[]{
                a.id(), nn(a.name()), nn(a.avatar()), nn(a.persona()), nn(a.model()), a.status().name(),
                utc(a.createdAt()), utc(a.updatedAt())};
    }

    protected void insert(Agent a) {
        jdbc.update("INSERT INTO agent " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", params(a));
    }

    @Override
    public Agent create(Agent a) {
        insert(a);
        return a;
    }

    @Override
    public Optional<Agent> find(String id) {
        return jdbc.query("SELECT * FROM agent" + agentFinal() + " WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    @Override
    public List<Agent> list() {
        return jdbc.query("SELECT * FROM agent" + agentFinal() + " ORDER BY updated_at DESC", mapper);
    }

    // update is backend-specific (MySQL UPDATE vs ClickHouse re-insert).

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    protected static String nn(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
