package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMemoryState;
import com.superagent.core.agent.AgentMemoryStateStore;
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
 * JDBC {@link AgentMemoryStateStore} shared by MySQL and ClickHouse. Both backends create the
 * table themselves; they differ only in {@link #save}, {@link #delete} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcAgentMemoryStateStore implements AgentMemoryStateStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcAgentMemoryStateStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String stateFinal();

    private final RowMapper<AgentMemoryState> mapper = (rs, n) -> new AgentMemoryState(
            rs.getString("agent_id"),
            emptyToNull(rs.getString("owner")),
            instant(rs, "summarized_until"),
            emptyToNull(rs.getString("last_model")),
            rs.getInt("session_failures"),
            emptyToNull(rs.getString("memory")),
            instant(rs, "updated_at"));

    @Override
    public Optional<AgentMemoryState> find(String agentId) {
        return jdbc.query("SELECT * FROM agent_memory" + stateFinal() + " WHERE company_id = ? AND agent_id = ?", mapper,
                        TenantSchema.company(), agentId)
                .stream().findFirst();
    }

    protected static final String COLUMNS =
            "(company_id, agent_id, owner, summarized_until, last_model, session_failures, memory, updated_at)";
    protected static final String VALUES = " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(AgentMemoryState s) {
        return new Object[]{TenantSchema.company(), s.agentId(), nn(s.owner()), utc(s.summarizedUntil()), nn(s.lastModel()),
                s.sessionFailures(), nn(s.memory()), utc(s.updatedAt())};
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
