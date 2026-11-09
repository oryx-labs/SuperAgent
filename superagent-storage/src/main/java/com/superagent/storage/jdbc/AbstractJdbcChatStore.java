package com.superagent.storage.jdbc;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
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
 * JDBC {@link ChatStore} shared by the MySQL and ClickHouse backends. Timestamps
 * are stored as UTC {@link LocalDateTime} so they round-trip identically across
 * drivers, independent of server or session time zone.
 *
 * <p>The two backends differ only in how a session update is applied and whether
 * session reads need a {@code FINAL} clause (ClickHouse collapses versions on
 * read); those are the abstract hooks below.
 */
abstract class AbstractJdbcChatStore implements ChatStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcChatStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** Clause appended to session SELECTs ("" for MySQL, " FINAL" for ClickHouse). */
    protected abstract String sessionFinal();

    private final RowMapper<ChatSession> sessionMapper = (rs, n) -> {
        String agentSessionId = rs.getString("agent_session_id");
        String agentId = rs.getString("agent_id");
        return new ChatSession(
                rs.getString("id"),
                rs.getString("title"),
                AgentType.valueOf(rs.getString("agent_type")),
                agentSessionId == null || agentSessionId.isEmpty() ? null : agentSessionId,
                rs.getString("workspace_dir"),
                agentId == null || agentId.isEmpty() ? null : agentId,
                SessionStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                emptyToNull(rs.getString("model")),
                emptyToNull(rs.getString("reason")),
                rs.getInt("cli_restarts"));
    };

    private final RowMapper<ChatMessage> messageMapper = (rs, n) -> new ChatMessage(
            rs.getString("id"),
            rs.getString("session_id"),
            MessageRole.valueOf(rs.getString("role")),
            rs.getString("content"),
            instant(rs, "created_at"),
            emptyToNull(rs.getString("author")));

    private final RowMapper<AgentEvent> eventMapper = (rs, n) -> {
        String messageId = rs.getString("message_id");
        return new AgentEvent(
                rs.getString("session_id"),
                messageId == null || messageId.isEmpty() ? null : messageId,
                rs.getLong("seq"),
                AgentEventType.valueOf(rs.getString("type")),
                rs.getString("payload"),
                instant(rs, "created_at"));
    };

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String nn(String value) {
        return value == null ? "" : value;
    }

    // --- sessions ---

    @Override
    public ChatSession createSession(ChatSession s) {
        insertSession(s);
        return s;
    }

    protected void insertSession(ChatSession s) {
        jdbc.update("INSERT INTO chat_session "
                        + "(company_id, id, title, agent_type, agent_session_id, workspace_dir, agent_id, status, created_at, updated_at, "
                        + "model, reason, cli_restarts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), s.id(), nn(s.title()), s.agentType().name(), nn(s.agentSessionId()),
                nn(s.workspaceDir()), nn(s.agentId()), s.status().name(), utc(s.createdAt()), utc(s.updatedAt()),
                nn(s.model()), nn(s.reason()), s.cliRestarts());
    }

    @Override
    public Optional<ChatSession> findSession(String sessionId) {
        List<ChatSession> rows = jdbc.query(
                "SELECT * FROM chat_session" + sessionFinal() + " WHERE company_id = ? AND id = ?", sessionMapper,
                TenantSchema.company(), sessionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<ChatSession> listSessions() {
        return jdbc.query("SELECT * FROM chat_session" + sessionFinal() + " WHERE company_id = ? ORDER BY updated_at DESC",
                sessionMapper, TenantSchema.company());
    }

    @Override
    public List<ChatSession> listSessionsByAgent(String agentId) {
        return jdbc.query("SELECT * FROM chat_session" + sessionFinal() + " WHERE company_id = ? AND agent_id = ? "
                + "ORDER BY updated_at DESC", sessionMapper, TenantSchema.company(), agentId);
    }

    // updateSession is backend-specific (MySQL UPDATE vs ClickHouse re-insert).

    // --- messages ---

    @Override
    public ChatMessage appendMessage(ChatMessage m) {
        jdbc.update("INSERT INTO chat_message (company_id, id, session_id, role, content, created_at, author) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), m.id(), m.sessionId(), m.role().name(), nn(m.content()), utc(m.createdAt()), nn(m.author()));
        return m;
    }

    @Override
    public List<ChatMessage> listMessages(String sessionId) {
        return jdbc.query("SELECT * FROM chat_message WHERE company_id = ? AND session_id = ? ORDER BY created_at",
                messageMapper, TenantSchema.company(), sessionId);
    }

    @Override
    public void updateMessage(String sessionId, String messageId, String content) {
        jdbc.update("UPDATE chat_message SET content = ? WHERE company_id = ? AND id = ? AND session_id = ?",
                content == null ? "" : content, TenantSchema.company(), messageId, sessionId);
    }

    // --- events ---

    @Override
    public void appendEvent(AgentEvent e) {
        jdbc.update("INSERT INTO agent_event (company_id, session_id, message_id, seq, type, payload, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), e.sessionId(), nn(e.messageId()), e.seq(), e.type().name(), nn(e.payload()), utc(e.createdAt()));
    }

    @Override
    public List<AgentEvent> listEvents(String sessionId) {
        return jdbc.query("SELECT * FROM agent_event WHERE company_id = ? AND session_id = ? ORDER BY created_at, seq",
                eventMapper, TenantSchema.company(), sessionId);
    }
}
