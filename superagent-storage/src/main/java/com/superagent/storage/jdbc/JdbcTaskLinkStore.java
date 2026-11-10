package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskLink;
import com.superagent.core.task.TaskLinkStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC task links on MySQL: one row per task, written once. */
public class JdbcTaskLinkStore implements TaskLinkStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS task_link (
              company_id     VARCHAR(64) NOT NULL,
              task_id        VARCHAR(64) NOT NULL,
              parent_id      VARCHAR(64),
              continues_from VARCHAR(64),
              created_at     DATETIME(3) NOT NULL,
              PRIMARY KEY (company_id, task_id),
              KEY idx_task_link_parent (company_id, parent_id),
              KEY idx_task_link_from (company_id, continues_from)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;


    private static final String SELECT = "SELECT task_id, parent_id, continues_from, created_at FROM task_link";

    private final JdbcTemplate jdbc;

    public JdbcTaskLinkStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    @Override
    public void save(TaskLink l) {
        jdbc.update("INSERT INTO task_link (company_id, task_id, parent_id, continues_from, created_at) VALUES (?, ?, ?, ?, ?)",
                TenantSchema.company(), l.taskId(), l.parentId(), l.continuesFrom(), Timestamp.from(l.createdAt() == null ? Instant.now() : l.createdAt()));
    }

    @Override
    public Optional<TaskLink> find(String taskId) {
        return jdbc.query(SELECT + " WHERE company_id = ? AND task_id = ?", (rs, i) -> link(rs), TenantSchema.company(), taskId).stream().findFirst();
    }

    @Override
    public List<TaskLink> all() {
        return jdbc.query(SELECT + " WHERE company_id = ?", (rs, i) -> link(rs), TenantSchema.company());
    }

    @Override
    public List<String> children(String parentId) {
        return jdbc.queryForList("SELECT task_id FROM task_link WHERE company_id = ? AND parent_id = ? ORDER BY created_at", String.class,
                TenantSchema.company(), parentId);
    }

    @Override
    public List<String> followUps(String taskId) {
        return jdbc.queryForList("SELECT task_id FROM task_link WHERE company_id = ? AND continues_from = ? ORDER BY created_at", String.class,
                TenantSchema.company(), taskId);
    }

    private static TaskLink link(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskLink(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant());
    }
}
