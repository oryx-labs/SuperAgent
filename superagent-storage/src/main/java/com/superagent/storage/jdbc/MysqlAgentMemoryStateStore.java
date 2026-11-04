package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMemoryState;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL agent-memory state store: self-creating table, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlAgentMemoryStateStore extends AbstractJdbcAgentMemoryStateStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS agent_memory (
              agent_id         VARCHAR(64)  NOT NULL,
              owner            VARCHAR(255),
              summarized_until DATETIME(3),
              last_model       VARCHAR(128),
              updated_at       DATETIME(3)  NOT NULL,
              PRIMARY KEY (agent_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlAgentMemoryStateStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String stateFinal() {
        return "";
    }

    @Override
    public AgentMemoryState save(AgentMemoryState s) {
        jdbc.update("INSERT INTO agent_memory " + COLUMNS + VALUES
                        + " ON DUPLICATE KEY UPDATE owner=VALUES(owner), summarized_until=VALUES(summarized_until), "
                        + "last_model=VALUES(last_model), updated_at=VALUES(updated_at)",
                params(s));
        return s;
    }

    @Override
    public void delete(String agentId) {
        jdbc.update("DELETE FROM agent_memory WHERE agent_id = ?", agentId);
    }
}
