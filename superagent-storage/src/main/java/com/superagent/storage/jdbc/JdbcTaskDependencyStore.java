package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskDependency;
import com.superagent.core.task.TaskDependencyStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** JDBC task dependencies on MySQL: one row per (task, task it waits for). */
public class JdbcTaskDependencyStore implements TaskDependencyStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS task_dependency (
              company_id  VARCHAR(64) NOT NULL,
              task_id     VARCHAR(64) NOT NULL,
              depends_on  VARCHAR(64) NOT NULL,
              created_at  DATETIME(3) NOT NULL,
              notified_at DATETIME(3),
              PRIMARY KEY (company_id, task_id, depends_on),
              KEY idx_task_dependency_on (company_id, depends_on)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final String SELECT = "SELECT task_id, depends_on, created_at, notified_at FROM task_dependency";

    private final JdbcTemplate jdbc;

    public JdbcTaskDependencyStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    @Override
    public void save(TaskDependency d) {
        jdbc.update("INSERT INTO task_dependency (company_id, task_id, depends_on, created_at, notified_at) VALUES (?, ?, ?, ?, ?)",
                TenantSchema.company(), d.taskId(), d.dependsOn(),
                Timestamp.from(d.createdAt() == null ? Instant.now() : d.createdAt()),
                d.notifiedAt() == null ? null : Timestamp.from(d.notifiedAt()));
    }

    @Override
    public List<TaskDependency> of(String taskId) {
        return jdbc.query(SELECT + " WHERE company_id = ? AND task_id = ? ORDER BY created_at", (rs, i) -> row(rs),
                TenantSchema.company(), taskId);
    }

    @Override
    public List<TaskDependency> all() {
        return jdbc.query(SELECT + " WHERE company_id = ?", (rs, i) -> row(rs), TenantSchema.company());
    }

    @Override
    public void markNotified(String taskId, String dependsOn, Instant at) {
        jdbc.update("UPDATE task_dependency SET notified_at = ? WHERE company_id = ? AND task_id = ? AND depends_on = ?",
                Timestamp.from(at), TenantSchema.company(), taskId, dependsOn);
    }

    @Override
    public void clear(String taskId) {
        jdbc.update("DELETE FROM task_dependency WHERE company_id = ? AND task_id = ?", TenantSchema.company(), taskId);
    }

    private static TaskDependency row(ResultSet rs) throws SQLException {
        Timestamp notified = rs.getTimestamp(4);
        return new TaskDependency(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                notified == null ? null : notified.toInstant());
    }
}
