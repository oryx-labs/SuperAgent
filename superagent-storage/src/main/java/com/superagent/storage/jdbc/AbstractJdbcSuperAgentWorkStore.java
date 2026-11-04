package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentWork;
import com.superagent.core.agent.SuperAgentWorkEvent;
import com.superagent.core.agent.SuperAgentWorkStore;
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
 * JDBC {@link SuperAgentWorkStore} shared by MySQL and ClickHouse. Both backends create their tables
 * themselves; they differ only in {@link #save} and whether reads need {@code FINAL}. Events are append-only.
 */
abstract class AbstractJdbcSuperAgentWorkStore implements SuperAgentWorkStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcSuperAgentWorkStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String workFinal();

    private final RowMapper<SuperAgentWork> mapper = (rs, n) -> new SuperAgentWork(
            rs.getString("task_id"),
            rs.getString("owner"),
            emptyToNull(rs.getString("plan_id")),
            rs.getString("title"),
            emptyToNull(rs.getString("member_id")),
            emptyToNull(rs.getString("member_name")),
            rs.getString("status"),
            emptyToNull(rs.getString("summary")),
            instant(rs, "assigned_at"),
            instant(rs, "finished_at"),
            instant(rs, "updated_at"));

    private final RowMapper<SuperAgentWorkEvent> eventMapper = (rs, n) -> new SuperAgentWorkEvent(
            rs.getString("id"),
            rs.getString("task_id"),
            rs.getString("owner"),
            rs.getString("kind"),
            emptyToNull(rs.getString("detail")),
            instant(rs, "at_time"));

    @Override
    public List<SuperAgentWork> list(String owner) {
        return jdbc.query("SELECT * FROM superagent_work" + workFinal() + " WHERE owner = ? ORDER BY assigned_at DESC",
                mapper, owner);
    }

    @Override
    public Optional<SuperAgentWork> find(String taskId) {
        return jdbc.query("SELECT * FROM superagent_work" + workFinal() + " WHERE task_id = ?", mapper, taskId)
                .stream().findFirst();
    }

    @Override
    public void appendEvent(SuperAgentWorkEvent e) {
        jdbc.update("INSERT INTO superagent_work_event (id, task_id, owner, kind, detail, at_time) VALUES (?, ?, ?, ?, ?, ?)",
                e.id(), e.taskId(), e.owner(), e.kind(), nn(e.detail()), utc(e.at()));
    }

    @Override
    public List<SuperAgentWorkEvent> events(String taskId) {
        return jdbc.query("SELECT * FROM superagent_work_event WHERE task_id = ? ORDER BY at_time", eventMapper, taskId);
    }

    protected static final String COLUMNS = "(task_id, owner, plan_id, title, member_id, member_name, status, summary, "
            + "assigned_at, finished_at, updated_at)";
    protected static final String VALUES = " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(SuperAgentWork w) {
        return new Object[]{w.taskId(), w.owner(), nn(w.planId()), w.title(), nn(w.memberId()), nn(w.memberName()),
                w.status(), nn(w.summary()), utc(w.assignedAt()), utc(w.finishedAt()), utc(w.updatedAt())};
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
