package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentState;
import com.superagent.core.agent.SuperAgentStateStore;
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
 * JDBC {@link SuperAgentStateStore} shared by MySQL and ClickHouse. Both backends create the
 * table themselves; they differ only in {@link #save} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcSuperAgentStateStore implements SuperAgentStateStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcSuperAgentStateStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String stateFinal();

    private final RowMapper<SuperAgentState> mapper = (rs, n) -> new SuperAgentState(
            rs.getString("owner"),
            rs.getString("agent_id"),
            instant(rs, "processed_until"),
            emptyToNull(rs.getString("plan_id")),
            emptyToNull(rs.getString("plan_status")),
            emptyToNull(rs.getString("plan_json")),
            emptyToNull(rs.getString("tracked_json")),
            instant(rs, "updated_at"));

    @Override
    public List<SuperAgentState> list() {
        return jdbc.query("SELECT * FROM superagent_state" + stateFinal(), mapper);
    }

    @Override
    public Optional<SuperAgentState> find(String owner) {
        return jdbc.query("SELECT * FROM superagent_state" + stateFinal() + " WHERE owner = ?", mapper, owner)
                .stream().findFirst();
    }

    protected static final String COLUMNS =
            "(owner, agent_id, processed_until, plan_id, plan_status, plan_json, tracked_json, updated_at)";
    protected static final String VALUES = " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(SuperAgentState s) {
        return new Object[]{s.owner(), s.agentId(), utc(s.processedUntil()), nn(s.planId()), nn(s.planStatus()),
                nn(s.planJson()), nn(s.trackedJson()), utc(s.updatedAt())};
    }

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
