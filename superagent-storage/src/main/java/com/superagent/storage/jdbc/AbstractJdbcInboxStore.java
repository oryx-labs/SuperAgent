package com.superagent.storage.jdbc;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxEventStore;
import com.superagent.core.inbox.InboxTodo;
import com.superagent.core.inbox.InboxTodoStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC stores for the inbox — received events, extracted to-dos and per-user source settings — shared
 * by MySQL and ClickHouse. Event ids are derived from source + external id by the caller, so the
 * primary key itself deduplicates redelivered messages. Backends differ in upserts, deletes and FINAL.
 */
abstract class AbstractJdbcInboxStore implements InboxEventStore, InboxTodoStore, EventSourceStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcInboxStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    // --- events ---

    protected static final String EVENT_COLUMNS = "(id, owner, source, external_id, chat_id, chat_name, chat_type, "
            + "thread_id, sender_id, sender_name, text, mentions_me, raw, priority, triage_reason, processed, handling, "
            + "occurred_at, received_at, updated_at)";
    protected static final String EVENT_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] eventParams(InboxEvent e) {
        return new Object[]{e.id(), e.owner(), e.source(), e.externalId(), nn(e.chatId()), nn(e.chatName()),
                nn(e.chatType()), nn(e.threadId()), nn(e.senderId()), nn(e.senderName()), nn(e.text()),
                e.mentionsMe() ? 1 : 0, nn(e.raw()), e.priority().name(), nn(e.triageReason()), e.processed() ? 1 : 0,
                nn(e.handling()), utc(e.occurredAt()), utc(e.receivedAt()), utc(e.updatedAt())};
    }

    private final RowMapper<InboxEvent> eventMapper = (rs, n) -> new InboxEvent(
            rs.getString("id"), rs.getString("owner"), rs.getString("source"), rs.getString("external_id"),
            emptyToNull(rs.getString("chat_id")), emptyToNull(rs.getString("chat_name")),
            emptyToNull(rs.getString("chat_type")), emptyToNull(rs.getString("thread_id")),
            emptyToNull(rs.getString("sender_id")), emptyToNull(rs.getString("sender_name")),
            nn(rs.getString("text")), rs.getInt("mentions_me") != 0, emptyToNull(rs.getString("raw")),
            EventPriority.valueOf(rs.getString("priority")), emptyToNull(rs.getString("triage_reason")),
            rs.getInt("processed") != 0, emptyToNull(rs.getString("handling")), instant(rs, "occurred_at"), instant(rs, "received_at"), instant(rs, "updated_at"));

    @Override
    public List<InboxEvent> pending(String owner, EventPriority priority, int limit) {
        return jdbc.query("SELECT * FROM inbox_event" + readFinal()
                        + " WHERE owner = ? AND priority = ? AND processed = 0 ORDER BY received_at LIMIT ?",
                eventMapper, owner, priority.name(), limit);
    }

    @Override
    public List<InboxEvent> recent(String owner, int limit) {
        return jdbc.query("SELECT * FROM inbox_event" + readFinal() + " WHERE owner = ? ORDER BY received_at DESC LIMIT ?",
                eventMapper, owner, limit);
    }

    @Override
    public Optional<InboxEvent> findEvent(String id) {
        return jdbc.query("SELECT * FROM inbox_event" + readFinal() + " WHERE id = ?", eventMapper, id).stream().findFirst();
    }

    protected boolean eventExists(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event" + readFinal() + " WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    // --- to-dos ---

    protected static final String TODO_COLUMNS = "(id, owner, title, detail, requester, due, event_ids, source, status, "
            + "task_id, created_at, updated_at)";
    protected static final String TODO_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] todoParams(InboxTodo t) {
        return new Object[]{t.id(), t.owner(), nn(t.title()), nn(t.detail()), nn(t.requester()), nn(t.due()),
                nn(t.eventIds()), nn(t.source()), t.status().name(), nn(t.taskId()), utc(t.createdAt()), utc(t.updatedAt())};
    }

    private final RowMapper<InboxTodo> todoMapper = (rs, n) -> new InboxTodo(
            rs.getString("id"), rs.getString("owner"), nn(rs.getString("title")), emptyToNull(rs.getString("detail")),
            emptyToNull(rs.getString("requester")), emptyToNull(rs.getString("due")), emptyToNull(rs.getString("event_ids")),
            emptyToNull(rs.getString("source")), InboxTodo.TodoStatus.valueOf(rs.getString("status")),
            emptyToNull(rs.getString("task_id")), instant(rs, "created_at"), instant(rs, "updated_at"));

    @Override
    public Optional<InboxTodo> find(String id) {
        return jdbc.query("SELECT * FROM inbox_todo" + readFinal() + " WHERE id = ?", todoMapper, id).stream().findFirst();
    }

    @Override
    public List<InboxTodo> list(String owner) {
        return jdbc.query("SELECT * FROM inbox_todo" + readFinal() + " WHERE owner = ? ORDER BY created_at DESC",
                todoMapper, owner);
    }

    // --- sources ---

    // Left over and written empty: vip_names / urgent_keywords (user-set triage rules), and secret_cipher /
    // status / last_error from when each user ran their own bot (the company app's live in the connector now).
    protected static final String SOURCE_COLUMNS = "(owner, type, app_id, secret_cipher, enabled, owner_external_id, "
            + "bind_code, vip_names, urgent_keywords, digest_time, timezone, secretary_agent_id, last_digest_date, "
            + "last_hourly_at, status, last_error, created_at, updated_at)";
    protected static final String SOURCE_VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] sourceParams(EventSource s) {
        return new Object[]{s.owner(), s.type(), nn(s.appId()), "", s.enabled() ? 1 : 0,
                nn(s.ownerExternalId()), nn(s.bindCode()), "", "", nn(s.digestTime()),
                nn(s.timezone()), nn(s.secretaryAgentId()), s.lastDigestDate() == null ? "" : s.lastDigestDate().toString(),
                utc(s.lastHourlyAt() == null ? Instant.EPOCH : s.lastHourlyAt()), "", "",
                utc(s.createdAt()), utc(s.updatedAt())};
    }

    private final RowMapper<EventSource> sourceMapper = (rs, n) -> {
        String digest = rs.getString("last_digest_date");
        Instant hourly = instant(rs, "last_hourly_at");
        return new EventSource(rs.getString("owner"), rs.getString("type"), emptyToNull(rs.getString("app_id")),
                rs.getInt("enabled") != 0,
                emptyToNull(rs.getString("owner_external_id")), emptyToNull(rs.getString("bind_code")),
                emptyToNull(rs.getString("digest_time")), emptyToNull(rs.getString("timezone")),
                emptyToNull(rs.getString("secretary_agent_id")),
                digest == null || digest.isEmpty() ? null : LocalDate.parse(digest),
                hourly == null || hourly.equals(Instant.EPOCH) ? null : hourly,
                instant(rs, "created_at"), instant(rs, "updated_at"));
    };

    @Override
    public Optional<EventSource> find(String owner, String type) {
        return jdbc.query("SELECT * FROM event_source" + readFinal() + " WHERE owner = ? AND type = ?",
                sourceMapper, owner, type).stream().findFirst();
    }

    @Override
    public List<EventSource> listEnabled(String type) {
        return jdbc.query("SELECT * FROM event_source" + readFinal() + " WHERE type = ? AND enabled = 1",
                sourceMapper, type);
    }

    @Override
    public List<EventSource> list() {
        return jdbc.query("SELECT * FROM event_source" + readFinal(), sourceMapper);
    }

    // --- helpers ---

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
