package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentWork;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL SuperAgent work store: self-creating tables, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlSuperAgentWorkStore extends AbstractJdbcSuperAgentWorkStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS superagent_work (
              task_id     VARCHAR(64)  NOT NULL,
              owner       VARCHAR(255) NOT NULL,
              plan_id     VARCHAR(64),
              title       VARCHAR(512) NOT NULL,
              member_id   VARCHAR(64),
              member_name VARCHAR(255),
              status      VARCHAR(32)  NOT NULL,
              summary     TEXT,
              assigned_at DATETIME(3)  NOT NULL,
              finished_at DATETIME(3),
              updated_at  DATETIME(3)  NOT NULL,
              message_id  VARCHAR(64),
              PRIMARY KEY (task_id),
              KEY idx_superagent_work_owner (owner, assigned_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS superagent_work_event (
              id      VARCHAR(64)  NOT NULL,
              task_id VARCHAR(64)  NOT NULL,
              owner   VARCHAR(255) NOT NULL,
              kind    VARCHAR(32)  NOT NULL,
              detail  TEXT,
              at_time DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_superagent_work_event (task_id, at_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlSuperAgentWorkStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        // Tables from before work remembered the reply that handed it out.
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'superagent_work' AND LOWER(column_name) = 'message_id'", Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE superagent_work ADD COLUMN message_id VARCHAR(64)");
        }
    }

    @Override
    protected String workFinal() {
        return "";
    }

    @Override
    public SuperAgentWork save(SuperAgentWork w) {
        jdbc.update("INSERT INTO superagent_work " + COLUMNS + VALUES
                        + " ON DUPLICATE KEY UPDATE plan_id=VALUES(plan_id), title=VALUES(title), member_id=VALUES(member_id), "
                        + "member_name=VALUES(member_name), status=VALUES(status), summary=VALUES(summary), "
                        + "finished_at=VALUES(finished_at), updated_at=VALUES(updated_at)",
                params(w));
        return w;
    }
}
