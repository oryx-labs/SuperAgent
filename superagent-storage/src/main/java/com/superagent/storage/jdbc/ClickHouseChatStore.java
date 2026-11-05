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
 *
 * <p>No auto schema init: apply {@code db/clickhouse/schema.sql} first (the
 * database must exist before the JDBC connection selects it).
 */
public class ClickHouseChatStore extends AbstractJdbcChatStore {

    public ClickHouseChatStore(JdbcTemplate jdbc) {
        super(jdbc, List.of("ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS author String"));
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
        jdbc.update("ALTER TABLE chat_message UPDATE content = ? WHERE id = ? AND session_id = ?",
                content == null ? "" : content, messageId, sessionId);
    }
}
