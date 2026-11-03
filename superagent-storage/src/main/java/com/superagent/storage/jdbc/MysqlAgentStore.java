package com.superagent.storage.jdbc;

import com.superagent.core.agent.Agent;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * MySQL agent store: {@code update} upserts via {@code ON DUPLICATE KEY UPDATE};
 * no {@code FINAL} clause is needed on reads. Creates its table on startup.
 */
public class MysqlAgentStore extends AbstractJdbcAgentStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS agent (
              id         VARCHAR(64)  NOT NULL,
              name       VARCHAR(255) NOT NULL,
              persona    MEDIUMTEXT,
              model      VARCHAR(128),
              status     VARCHAR(32)  NOT NULL,
              created_at DATETIME(3)  NOT NULL,
              updated_at DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_agent_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlAgentStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String agentFinal() {
        return "";
    }

    @Override
    public void update(Agent a) {
        jdbc.update("INSERT INTO agent " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE name=VALUES(name), persona=VALUES(persona), "
                        + "model=VALUES(model), status=VALUES(status), updated_at=VALUES(updated_at)",
                params(a));
    }
}
