package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMember;
import com.superagent.core.agent.AgentMemberStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** JDBC {@link AgentMemberStore} shared by MySQL and ClickHouse; backends differ in the upsert and FINAL. */
abstract class AbstractJdbcAgentMemberStore implements AgentMemberStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcAgentMemberStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String INSERT = "(agent_id, username, added_by, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";

    protected Object[] params(AgentMember m) {
        return new Object[]{m.agentId(), m.username(), m.addedBy() == null ? "" : m.addedBy(), m.active() ? 1 : 0,
                utc(m.createdAt()), utc(m.updatedAt())};
    }

    private final RowMapper<AgentMember> mapper = (rs, n) -> new AgentMember(rs.getString("agent_id"),
            rs.getString("username"), rs.getString("added_by"), rs.getInt("active") != 0,
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    @Override
    public List<AgentMember> members(String agentId) {
        return jdbc.query("SELECT * FROM agent_member" + readFinal() + " WHERE agent_id = ? AND active = 1 ORDER BY created_at",
                mapper, agentId);
    }

    @Override
    public List<String> agentsOf(String username) {
        return jdbc.query("SELECT agent_id FROM agent_member" + readFinal() + " WHERE username = ? AND active = 1",
                (rs, n) -> rs.getString("agent_id"), username);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }
}
