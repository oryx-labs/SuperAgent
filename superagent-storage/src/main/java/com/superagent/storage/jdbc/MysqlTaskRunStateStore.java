package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskRunState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL run-state store: self-creating table, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlTaskRunStateStore extends AbstractJdbcTaskRunStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS task_run_state (
              company_id       VARCHAR(64) NOT NULL,
              task_id          VARCHAR(64) NOT NULL,
              agent_session_id VARCHAR(64),
              failure_count    INT         NOT NULL DEFAULT 0,
              attempt_count    INT         NOT NULL DEFAULT 0,
              updated_at       DATETIME(3) NOT NULL,
              interrupted      BOOLEAN     NOT NULL DEFAULT FALSE,
              PRIMARY KEY (company_id, task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlTaskRunStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        ensureInterrupted();
    }

    private void ensureInterrupted() {
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'task_run_state' AND LOWER(column_name) = 'interrupted'", Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE task_run_state ADD COLUMN interrupted BOOLEAN NOT NULL DEFAULT FALSE");
        }
    }

    @Override
    protected String stateFinal() {
        return "";
    }

    @Override
    public TaskRunState save(TaskRunState s) {
        jdbc.update("INSERT INTO task_run_state (company_id, task_id, agent_session_id, failure_count, attempt_count, updated_at, interrupted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE agent_session_id=VALUES(agent_session_id), "
                        + "failure_count=VALUES(failure_count), attempt_count=VALUES(attempt_count), "
                        + "updated_at=VALUES(updated_at), interrupted=VALUES(interrupted)",
                TenantSchema.company(), s.taskId(), nn(s.agentSessionId()), s.failureCount(), s.attemptCount(), utc(s.updatedAt()),
                s.interruptedByRestart());
        return s;
    }

    @Override
    public void delete(String taskId) {
        jdbc.update("DELETE FROM task_run_state WHERE company_id = ? AND task_id = ?", TenantSchema.company(), taskId);
    }
}
