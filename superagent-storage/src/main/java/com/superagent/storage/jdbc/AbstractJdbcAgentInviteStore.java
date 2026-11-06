package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentInvite;
import com.superagent.core.agent.AgentInviteStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** JDBC {@link AgentInviteStore} shared by MySQL and ClickHouse; usernames are kept comma-separated. */
abstract class AbstractJdbcAgentInviteStore implements AgentInviteStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcAgentInviteStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String INSERT = "(id, agent_id, owner, usernames, session_id, message_id, status, created_at, "
            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(AgentInvite i) {
        return new Object[]{i.id(), i.agentId(), i.owner(), String.join(",", i.usernames()), nn(i.sessionId()),
                nn(i.messageId()), i.status().name(), utc(i.createdAt()), utc(i.updatedAt())};
    }

    private final RowMapper<AgentInvite> mapper = (rs, n) -> {
        String names = rs.getString("usernames");
        return new AgentInvite(rs.getString("id"), rs.getString("agent_id"), rs.getString("owner"),
                names == null || names.isEmpty() ? List.of() : Arrays.asList(names.split(",")),
                emptyToNull(rs.getString("session_id")), emptyToNull(rs.getString("message_id")),
                AgentInvite.Status.valueOf(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    };

    @Override
    public Optional<AgentInvite> find(String id) {
        return jdbc.query("SELECT * FROM agent_invite" + readFinal() + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    @Override
    public List<AgentInvite> forAgent(String agentId) {
        return jdbc.query("SELECT * FROM agent_invite" + readFinal() + " WHERE agent_id = ? ORDER BY created_at DESC",
                mapper, agentId);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
