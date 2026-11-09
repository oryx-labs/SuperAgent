package com.superagent.storage.jdbc;

import com.superagent.core.chat.ChatSession;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * MySQL {@link com.superagent.core.chat.ChatStore}. Session metadata is updated
 * in place with {@code UPDATE}; no {@code FINAL} clause is needed on reads.
 *
 * <p>Creates its tables on startup ({@code CREATE TABLE IF NOT EXISTS}), like every store.
 */
public class MysqlChatStore extends AbstractJdbcChatStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS chat_session (
              company_id       VARCHAR(64)  NOT NULL,
              id               VARCHAR(64)  NOT NULL,
              title            VARCHAR(255),
              agent_type       VARCHAR(32)  NOT NULL,
              agent_session_id VARCHAR(64),
              workspace_dir    VARCHAR(1024),
              agent_id         VARCHAR(64),
              status           VARCHAR(32)  NOT NULL,
              created_at       DATETIME(3)  NOT NULL,
              updated_at       DATETIME(3)  NOT NULL,
              model            VARCHAR(128),
              reason           VARCHAR(32),
              cli_restarts     INT          NOT NULL DEFAULT 0,
              PRIMARY KEY (company_id, id),
              KEY idx_session_updated (company_id, updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_message (
              company_id VARCHAR(64) NOT NULL,
              id         VARCHAR(64) NOT NULL,
              session_id VARCHAR(64) NOT NULL,
              role       VARCHAR(16) NOT NULL,
              content    MEDIUMTEXT,
              created_at DATETIME(3) NOT NULL,
              PRIMARY KEY (company_id, id),
              KEY idx_message_session (company_id, session_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS agent_event (
              company_id VARCHAR(64) NOT NULL,
              session_id VARCHAR(64) NOT NULL,
              message_id VARCHAR(64),
              seq        BIGINT      NOT NULL,
              type       VARCHAR(32) NOT NULL,
              payload    MEDIUMTEXT,
              created_at DATETIME(3) NOT NULL,
              KEY idx_event_session (company_id, session_id, created_at, seq)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlChatStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        // CREATE TABLE IF NOT EXISTS won't alter a chat_session table created before agent_id existed.
        ensureColumn("chat_session", "agent_id", "VARCHAR(64)");
        ensureColumn("chat_message", "author", "VARCHAR(128)");
        ensureColumn("chat_session", "model", "VARCHAR(128)");
        ensureColumn("chat_session", "reason", "VARCHAR(32)");
        ensureColumn("chat_session", "cli_restarts", "INT NOT NULL DEFAULT 0");
    }

    /** Adds a column to an already-created table idempotently (MySQL has no ADD COLUMN IF NOT EXISTS). */
    private void ensureColumn(String table, String column, String type) {
        Integer present = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = SCHEMA() AND LOWER(table_name) = LOWER(?) "
                        + "AND LOWER(column_name) = LOWER(?)",
                Integer.class, table, column);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    @Override
    protected String sessionFinal() {
        return "";
    }

    @Override
    public void updateSession(ChatSession s) {
        jdbc.update("UPDATE chat_session SET title = ?, agent_type = ?, agent_session_id = ?, status = ?, updated_at = ?, "
                        + "cli_restarts = ? WHERE company_id = ? AND id = ?",
                s.title() == null ? "" : s.title(),
                s.agentType().name(),
                s.agentSessionId() == null ? "" : s.agentSessionId(),
                s.status().name(),
                java.time.LocalDateTime.ofInstant(s.updatedAt(), java.time.ZoneOffset.UTC),
                s.cliRestarts(),
                TenantSchema.company(),
                s.id());
    }
}
