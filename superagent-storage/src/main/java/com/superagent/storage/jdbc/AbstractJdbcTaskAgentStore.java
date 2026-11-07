package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskOrigin;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link TaskAgentStore} shared by MySQL and ClickHouse. One row per task (the task id is the key, so a
 * re-link is idempotent); backends differ only in the insert/upsert and FINAL.
 */
abstract class AbstractJdbcTaskAgentStore implements TaskAgentStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcTaskAgentStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected abstract void upsert(String taskId, String agentId, String sessionId, String messageId, LocalDateTime at);

    @Override
    public void link(String taskId, String agentId, String sessionId, String messageId) {
        if (taskId == null || agentId == null) {
            return;
        }
        upsert(taskId, agentId, sessionId, messageId, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }

    @Override
    public List<String> tasksForAgent(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        return jdbc.queryForList("SELECT task_id FROM task_agent" + readFinal()
                + " WHERE agent_id = ? ORDER BY created_at DESC", String.class, agentId);
    }

    @Override
    public Optional<String> agentOf(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT agent_id FROM task_agent" + readFinal() + " WHERE task_id = ?",
                String.class, taskId).stream().findFirst();
    }

    @Override
    public Optional<TaskOrigin> origin(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return origins(" WHERE task_id = ?", taskId).stream().findFirst();
    }

    @Override
    public List<TaskOrigin> originsForAgent(String agentId) {
        if (agentId == null) {
            return List.of();
        }
        return origins(" WHERE agent_id = ? ORDER BY created_at DESC", agentId);
    }

    private List<TaskOrigin> origins(String where, String arg) {
        return jdbc.query("SELECT task_id, agent_id, session_id, message_id FROM task_agent" + readFinal() + where,
                (rs, i) -> new TaskOrigin(rs.getString(1), rs.getString(2), blankToNull(rs.getString(3)),
                        blankToNull(rs.getString(4))), arg);
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s; // ClickHouse has no NULL here, only ""
    }
}
