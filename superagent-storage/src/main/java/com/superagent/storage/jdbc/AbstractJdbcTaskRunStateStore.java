package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskRunState;
import com.superagent.core.task.TaskRunStateStore;
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
 * JDBC {@link TaskRunStateStore} shared by MySQL and ClickHouse. Both backends
 * create the table themselves (so no separate schema file is required); they
 * differ only in {@link #save} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcTaskRunStateStore implements TaskRunStateStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcTaskRunStateStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String stateFinal();

    private final RowMapper<TaskRunState> mapper = (rs, n) -> new TaskRunState(
            rs.getString("task_id"),
            emptyToNull(rs.getString("agent_session_id")),
            rs.getInt("failure_count"),
            rs.getInt("attempt_count"),
            instant(rs, "updated_at"));

    @Override
    public Optional<TaskRunState> find(String taskId) {
        return jdbc.query("SELECT * FROM task_run_state" + stateFinal() + " WHERE task_id = ?", mapper, taskId)
                .stream().findFirst();
    }

    protected void insert(TaskRunState s) {
        jdbc.update("INSERT INTO task_run_state (task_id, agent_session_id, failure_count, attempt_count, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                s.taskId(), nn(s.agentSessionId()), s.failureCount(), s.attemptCount(), utc(s.updatedAt()));
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
