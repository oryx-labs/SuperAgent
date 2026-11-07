package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/** ClickHouse task→agent links: {@code ReplacingMergeTree(created_at)} keyed by task id; reads use FINAL. */
public class ClickHouseTaskAgentStore extends AbstractJdbcTaskAgentStore {

    public ClickHouseTaskAgentStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS task_agent (
                  task_id String, agent_id String, session_id String, message_id String, created_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(created_at)
                ORDER BY (task_id)
                """);
        jdbc.execute("ALTER TABLE task_agent ADD COLUMN IF NOT EXISTS session_id String");
        jdbc.execute("ALTER TABLE task_agent ADD COLUMN IF NOT EXISTS message_id String");
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    protected void upsert(String taskId, String agentId, String sessionId, String messageId, LocalDateTime at) {
        jdbc.update("INSERT INTO task_agent (task_id, agent_id, session_id, message_id, created_at) VALUES (?, ?, ?, ?, ?)",
                taskId, agentId, sessionId == null ? "" : sessionId, messageId == null ? "" : messageId, at);
    }
}
