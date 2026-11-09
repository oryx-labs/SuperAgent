package com.superagent.storage.jdbc;

import com.superagent.core.chat.ChatSession;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse {@link com.superagent.core.chat.ChatStore}. The session table is a
 * {@code ReplacingMergeTree(updated_at)} keyed by id, so an update is written as
 * a new row (a newer version) and reads use {@code FINAL} to collapse to the
 * latest. Messages and events are append-only; a message edit is applied as an
 * {@code ALTER TABLE ... UPDATE} mutation (asynchronous in ClickHouse).
 */
public class ClickHouseChatStore extends AbstractJdbcChatStore {

    public ClickHouseChatStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("""
                CREATE TABLE IF NOT EXISTS chat_session (
                  company_id String,
                  id String,
                  title String,
                  agent_type String,
                  agent_session_id String,
                  workspace_dir String,
                  agent_id String,
                  status String,
                  created_at DateTime64(3, 'UTC'),
                  updated_at DateTime64(3, 'UTC'),
                  model String,
                  reason String,
                  cli_restarts Int32 DEFAULT 0
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (company_id, id)
                """, """
                CREATE TABLE IF NOT EXISTS chat_message (
                  company_id String,
                  id String,
                  session_id String,
                  role String,
                  content String,
                  created_at DateTime64(3, 'UTC'),
                  author String
                ) ENGINE = MergeTree
                ORDER BY (company_id, session_id, created_at)
                """, """
                CREATE TABLE IF NOT EXISTS agent_event (
                  company_id String,
                  session_id String,
                  message_id String,
                  seq Int64,
                  type String,
                  payload String,
                  created_at DateTime64(3, 'UTC')
                ) ENGINE = MergeTree
                ORDER BY (company_id, session_id, created_at, seq)
                """, "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS model String",
                "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS reason String",
                "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS cli_restarts Int32 DEFAULT 0"));
    }

    @Override
    protected String sessionFinal() {
        return " FINAL";
    }

    @Override
    public void updateSession(ChatSession s) {
        // ReplacingMergeTree: re-insert the full row; FINAL reads keep the newest.
        insertSession(s);
    }

    @Override
    public void updateMessage(String sessionId, String messageId, String content) {
        jdbc.update("ALTER TABLE chat_message UPDATE content = ? WHERE company_id = ? AND id = ? AND session_id = ?",
                content == null ? "" : content, TenantSchema.company(), messageId, sessionId);
    }
}
