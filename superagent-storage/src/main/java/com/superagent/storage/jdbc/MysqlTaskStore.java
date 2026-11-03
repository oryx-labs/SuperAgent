package com.superagent.storage.jdbc;

import com.superagent.core.task.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL task store: upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlTaskStore extends AbstractJdbcTaskStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS task (
              id            VARCHAR(64)   NOT NULL,
              title         VARCHAR(512)  NOT NULL,
              brief         MEDIUMTEXT,
              source        VARCHAR(16)   NOT NULL,
              repo          VARCHAR(512),
              branch        VARCHAR(255),
              flow          VARCHAR(255),
              status        VARCHAR(32)   NOT NULL,
              progress      INT           NOT NULL DEFAULT 0,
              spend         DECIMAL(12,2) NOT NULL DEFAULT 0,
              model         VARCHAR(128),
              agent_type    VARCHAR(32)   NOT NULL,
              workspace_dir VARCHAR(1024),
              created_by    VARCHAR(255),
              error         MEDIUMTEXT,
              created_at    DATETIME(3)   NOT NULL,
              started_at    DATETIME(3),
              finished_at   DATETIME(3),
              updated_at    DATETIME(3)   NOT NULL,
              PRIMARY KEY (id),
              KEY idx_task_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlTaskStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String taskFinal() {
        return "";
    }

    @Override
    public Task save(Task t) {
        jdbc.update("INSERT INTO task " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE title=VALUES(title), brief=VALUES(brief), source=VALUES(source), "
                        + "repo=VALUES(repo), branch=VALUES(branch), flow=VALUES(flow), status=VALUES(status), "
                        + "progress=VALUES(progress), spend=VALUES(spend), model=VALUES(model), "
                        + "agent_type=VALUES(agent_type), workspace_dir=VALUES(workspace_dir), "
                        + "created_by=VALUES(created_by), error=VALUES(error), started_at=VALUES(started_at), "
                        + "finished_at=VALUES(finished_at), updated_at=VALUES(updated_at)",
                params(t));
        return t;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM task WHERE id = ?", id);
    }
}
