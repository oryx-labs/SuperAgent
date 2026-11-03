package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskRunState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL run-state store: self-creating table, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlTaskRunStateStore extends AbstractJdbcTaskRunStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS task_run_state (
              task_id          VARCHAR(64) NOT NULL,
              agent_session_id VARCHAR(64),
              failure_count    INT         NOT NULL DEFAULT 0,
              attempt_count    INT         NOT NULL DEFAULT 0,
              updated_at       DATETIME(3) NOT NULL,
              PRIMARY KEY (task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlTaskRunStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String stateFinal() {
        return "";
    }

    @Override
    public TaskRunState save(TaskRunState s) {
        jdbc.update("INSERT INTO task_run_state (task_id, agent_session_id, failure_count, attempt_count, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE agent_session_id=VALUES(agent_session_id), "
                        + "failure_count=VALUES(failure_count), attempt_count=VALUES(attempt_count), "
                        + "updated_at=VALUES(updated_at)",
                s.taskId(), nn(s.agentSessionId()), s.failureCount(), s.attemptCount(), utc(s.updatedAt()));
        return s;
    }

    @Override
    public void delete(String taskId) {
        jdbc.update("DELETE FROM task_run_state WHERE task_id = ?", taskId);
    }
}
