package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/** MySQL task→agent links: self-creating table, idempotent upsert via REPLACE on the task-id key. */
public class MysqlTaskAgentStore extends AbstractJdbcTaskAgentStore {

    public MysqlTaskAgentStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS task_agent (
                  company_id VARCHAR(64) NOT NULL,
                  task_id    VARCHAR(64) NOT NULL,
                  agent_id   VARCHAR(64) NOT NULL,
                  session_id VARCHAR(64),
                  message_id VARCHAR(64),
                  created_at DATETIME(3) NOT NULL,
                  PRIMARY KEY (company_id, task_id),
                  KEY idx_task_agent (company_id, agent_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // Tables from before tasks remembered the chat reply that set them off.
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'task_agent' AND LOWER(column_name) = 'message_id'", Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE task_agent ADD COLUMN session_id VARCHAR(64), ADD COLUMN message_id VARCHAR(64)");
        }
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    protected void upsert(String taskId, String agentId, String sessionId, String messageId, LocalDateTime at) {
        jdbc.update("REPLACE INTO task_agent (company_id, task_id, agent_id, session_id, message_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), taskId, agentId, sessionId, messageId, at);
    }
}
