package com.superagent.storage.jdbc;

import com.superagent.core.chat.ChatSession;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * MySQL {@link com.superagent.core.chat.ChatStore}. Session metadata is updated
 * in place with {@code UPDATE}; no {@code FINAL} clause is needed on reads.
 *
 * <p>Creates its tables on startup ({@code CREATE TABLE IF NOT EXISTS}) so the
 * default backend works out of the box; the canonical schema also lives in
 * {@code db/mysql/schema.sql}.
 */
public class MysqlChatStore extends AbstractJdbcChatStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS chat_session (
              id               VARCHAR(64)  NOT NULL,
              title            VARCHAR(255),
              agent_type       VARCHAR(32)  NOT NULL,
              agent_session_id VARCHAR(64),
              workspace_dir    VARCHAR(1024),
              status           VARCHAR(32)  NOT NULL,
              created_at       DATETIME(3)  NOT NULL,
              updated_at       DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_session_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_message (
              id         VARCHAR(64) NOT NULL,
              session_id VARCHAR(64) NOT NULL,
              role       VARCHAR(16) NOT NULL,
              content    MEDIUMTEXT,
              created_at DATETIME(3) NOT NULL,
              PRIMARY KEY (id),
              KEY idx_message_session (session_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS agent_event (
              session_id VARCHAR(64) NOT NULL,
              message_id VARCHAR(64),
              seq        BIGINT      NOT NULL,
              type       VARCHAR(32) NOT NULL,
              payload    MEDIUMTEXT,
              created_at DATETIME(3) NOT NULL,
              KEY idx_event_session (session_id, created_at, seq)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlChatStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String sessionFinal() {
        return "";
    }

    @Override
    public void updateSession(ChatSession s) {
        jdbc.update("UPDATE chat_session SET title = ?, agent_session_id = ?, status = ?, updated_at = ? WHERE id = ?",
                s.title() == null ? "" : s.title(),
                s.agentSessionId() == null ? "" : s.agentSessionId(),
                s.status().name(),
                java.time.LocalDateTime.ofInstant(s.updatedAt(), java.time.ZoneOffset.UTC),
                s.id());
    }
}
