package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/** MySQL task→agent links: self-creating table, idempotent upsert via REPLACE on the task-id key. */
public class MysqlTaskAgentStore extends AbstractJdbcTaskAgentStore {

    public MysqlTaskAgentStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS task_agent (
                  task_id    VARCHAR(64) NOT NULL,
                  agent_id   VARCHAR(64) NOT NULL,
                  created_at DATETIME(3) NOT NULL,
                  PRIMARY KEY (task_id),
                  KEY idx_task_agent (agent_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    protected void upsert(String taskId, String agentId, LocalDateTime at) {
        jdbc.update("REPLACE INTO task_agent (task_id, agent_id, created_at) VALUES (?, ?, ?)",
                taskId, agentId, at);
    }
}
