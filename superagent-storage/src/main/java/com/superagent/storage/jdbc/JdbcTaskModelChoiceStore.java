package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskModelChoice;
import com.superagent.core.task.TaskModelChoiceStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** JDBC task model choices on MySQL: rows are only ever appended (a task's next choice gets the next {@code seq}). */
public class JdbcTaskModelChoiceStore implements TaskModelChoiceStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS task_model_choice (
              company_id VARCHAR(64)  NOT NULL,
              task_id    VARCHAR(64)  NOT NULL,
              seq        INT          NOT NULL,
              decided_by VARCHAR(16)  NOT NULL,
              difficulty VARCHAR(16),
              tier       VARCHAR(16),
              model      VARCHAR(128),
              cli        VARCHAR(32),
              judge      VARCHAR(128),
              candidates TEXT,
              reasoning  TEXT,
              created_at DATETIME(3)  NOT NULL,
              PRIMARY KEY (company_id, task_id, seq)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;


    private final JdbcTemplate jdbc;

    public JdbcTaskModelChoiceStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    @Override
    public void save(TaskModelChoice c) {
        jdbc.update("INSERT INTO task_model_choice (company_id, task_id, seq, decided_by, difficulty, tier, model, cli, judge, "
                        + "candidates, reasoning, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), c.taskId(), c.seq(), c.by(), c.difficulty(), c.tier(), c.model(), c.cli(), c.judge(), c.candidates(),
                c.reasoning(), Timestamp.from(c.createdAt() == null ? Instant.now() : c.createdAt()));
    }

    @Override
    public List<TaskModelChoice> list(String taskId) {
        return jdbc.query("SELECT task_id, seq, decided_by, difficulty, tier, model, cli, judge, candidates, reasoning, "
                        + "created_at FROM task_model_choice WHERE company_id = ? AND task_id = ? ORDER BY seq",
                (rs, i) -> new TaskModelChoice(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getTimestamp(11).toInstant()), TenantSchema.company(), taskId);
    }
}
