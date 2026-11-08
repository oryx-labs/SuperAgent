package com.superagent.storage.jdbc;

import com.superagent.core.chat.AgentType;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link TaskStore} shared by MySQL and ClickHouse, mirroring
 * {@link AbstractJdbcConnectorStore}. Timestamps are stored as UTC
 * {@link LocalDateTime}; {@code started_at}/{@code finished_at} are nullable.
 * Backends differ only in {@link #save} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcTaskStore implements TaskStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcTaskStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String taskFinal();

    private final RowMapper<Task> mapper = (rs, n) -> new Task(
            rs.getString("id"),
            rs.getString("title"),
            emptyToNull(rs.getString("brief")),
            TaskSource.valueOf(rs.getString("source")),
            emptyToNull(rs.getString("repo")),
            emptyToNull(rs.getString("branch")),
            emptyToNull(rs.getString("flow")),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getInt("progress"),
            rs.getBigDecimal("spend"),
            emptyToNull(rs.getString("model")),
            AgentType.valueOf(rs.getString("agent_type")),
            emptyToNull(rs.getString("workspace_dir")),
            emptyToNull(rs.getString("created_by")),
            emptyToNull(rs.getString("error")),
            instant(rs, "created_at"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            instant(rs, "updated_at"),
            emptyToNull(rs.getString("tier")));

    @Override
    public List<Task> listTasks() {
        return jdbc.query("SELECT * FROM task" + taskFinal() + " WHERE company_id = ? ORDER BY created_at DESC", mapper,
                TenantSchema.company());
    }

    @Override
    public Optional<Task> findTask(String id) {
        return jdbc.query("SELECT * FROM task" + taskFinal() + " WHERE company_id = ? AND id = ?", mapper,
                        TenantSchema.company(), id)
                .stream().findFirst();
    }

    protected static final String COLUMNS =
            "(company_id, id, title, brief, source, repo, branch, flow, status, progress, spend, model, agent_type, "
                    + "workspace_dir, created_by, error, created_at, started_at, finished_at, updated_at, tier)";

    protected Object[] params(Task t) {
        return new Object[]{
                TenantSchema.company(), t.id(), t.title(), nn(t.brief()), t.source().name(), nn(t.repo()), nn(t.branch()), nn(t.flow()),
                t.status().name(), t.progress(), t.spend() == null ? BigDecimal.ZERO : t.spend(),
                nn(t.model()), t.agentType().name(), nn(t.workspaceDir()), nn(t.createdBy()), nn(t.error()),
                utc(t.createdAt()), utc(t.startedAt()), utc(t.finishedAt()), utc(t.updatedAt()), nn(t.tier())};
    }

    protected void insert(Task t) {
        jdbc.update("INSERT INTO task " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                params(t));
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
