package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL SuperAgent state store: self-creating table, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlSuperAgentStateStore extends AbstractJdbcSuperAgentStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS superagent_state (
              owner           VARCHAR(255) NOT NULL,
              agent_id        VARCHAR(64)  NOT NULL,
              processed_until DATETIME(3),
              plan_id         VARCHAR(64),
              plan_status     VARCHAR(16),
              plan_json       MEDIUMTEXT,
              tracked_json    MEDIUMTEXT,
              updated_at      DATETIME(3)  NOT NULL,
              PRIMARY KEY (owner)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlSuperAgentStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String stateFinal() {
        return "";
    }

    @Override
    public SuperAgentState save(SuperAgentState s) {
        jdbc.update("INSERT INTO superagent_state " + COLUMNS + VALUES
                        + " ON DUPLICATE KEY UPDATE agent_id=VALUES(agent_id), processed_until=VALUES(processed_until), "
                        + "plan_id=VALUES(plan_id), plan_status=VALUES(plan_status), plan_json=VALUES(plan_json), "
                        + "tracked_json=VALUES(tracked_json), updated_at=VALUES(updated_at)",
                params(s));
        return s;
    }
}
