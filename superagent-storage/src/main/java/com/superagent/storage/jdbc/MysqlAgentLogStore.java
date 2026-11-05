package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL agent run-log store: self-creating table, plain DELETE for retention. */
public class MysqlAgentLogStore extends AbstractJdbcAgentLogStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS agent_log (
              id       VARCHAR(64)  NOT NULL,
              agent_id VARCHAR(64)  NOT NULL,
              owner    VARCHAR(255),
              at_time  DATETIME(3)  NOT NULL,
              level    VARCHAR(8)   NOT NULL,
              category VARCHAR(16)  NOT NULL,
              summary  VARCHAR(1024) NOT NULL,
              detail   TEXT,
              PRIMARY KEY (id),
              KEY idx_agent_log (agent_id, at_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlAgentLogStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected void deleteWhere(String where, Object... args) {
        jdbc.update("DELETE FROM agent_log WHERE " + where, args);
    }
}
