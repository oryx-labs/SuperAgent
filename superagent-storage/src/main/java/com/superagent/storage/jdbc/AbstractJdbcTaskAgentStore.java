package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskAgentStore;
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

    protected abstract void upsert(String taskId, String agentId, LocalDateTime at);

    @Override
    public void link(String taskId, String agentId) {
        if (taskId == null || agentId == null) {
            return;
        }
        upsert(taskId, agentId, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
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
}
