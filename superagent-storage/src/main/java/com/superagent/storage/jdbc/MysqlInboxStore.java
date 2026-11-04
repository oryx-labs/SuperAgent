package com.superagent.storage.jdbc;

import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxTodo;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/** MySQL inbox stores: self-creating tables, {@code INSERT IGNORE} dedupe, upserts via ON DUPLICATE KEY. */
public class MysqlInboxStore extends AbstractJdbcInboxStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS inbox_event (
              id            VARCHAR(64)  NOT NULL,
              owner         VARCHAR(128) NOT NULL,
              source        VARCHAR(32)  NOT NULL,
              external_id   VARCHAR(255) NOT NULL,
              chat_id       VARCHAR(128),
              chat_name     VARCHAR(255),
              chat_type     VARCHAR(16),
              thread_id     VARCHAR(128),
              sender_id     VARCHAR(128),
              sender_name   VARCHAR(255),
              text          MEDIUMTEXT,
              mentions_me   TINYINT      NOT NULL,
              raw           MEDIUMTEXT,
              priority      VARCHAR(16)  NOT NULL,
              triage_reason VARCHAR(255),
              processed     TINYINT      NOT NULL,
              occurred_at   DATETIME(3),
              received_at   DATETIME(3)  NOT NULL,
              updated_at    DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_inbox_pending (owner, priority, processed, received_at),
              KEY idx_inbox_received (received_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS inbox_todo (
              id         VARCHAR(64)  NOT NULL,
              owner      VARCHAR(128) NOT NULL,
              title      VARCHAR(500) NOT NULL,
              detail     TEXT,
              requester  VARCHAR(255),
              due        VARCHAR(128),
              event_ids  TEXT,
              source     VARCHAR(255),
              status     VARCHAR(16)  NOT NULL,
              task_id    VARCHAR(64),
              created_at DATETIME(3)  NOT NULL,
              updated_at DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_todo_owner (owner, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS event_source (
              owner              VARCHAR(128) NOT NULL,
              type               VARCHAR(32)  NOT NULL,
              app_id             VARCHAR(128),
              secret_cipher      VARCHAR(1024),
              enabled            TINYINT      NOT NULL,
              owner_external_id  VARCHAR(128),
              bind_code          VARCHAR(32),
              vip_names          TEXT,
              urgent_keywords    TEXT,
              digest_time        VARCHAR(8),
              timezone           VARCHAR(64),
              secretary_agent_id VARCHAR(64),
              last_digest_date   VARCHAR(16),
              last_hourly_at     DATETIME(3),
              status             VARCHAR(32),
              last_error         VARCHAR(1024),
              created_at         DATETIME(3)  NOT NULL,
              updated_at         DATETIME(3)  NOT NULL,
              PRIMARY KEY (owner, type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlInboxStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public boolean insertIfAbsent(InboxEvent e) {
        return jdbc.update("INSERT IGNORE INTO inbox_event " + EVENT_COLUMNS + " VALUES " + EVENT_VALUES, eventParams(e)) > 0;
    }

    @Override
    public void update(InboxEvent e) {
        jdbc.update("REPLACE INTO inbox_event " + EVENT_COLUMNS + " VALUES " + EVENT_VALUES, eventParams(e));
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM inbox_event WHERE received_at < ?", utc(cutoff));
    }

    @Override
    public void save(InboxTodo t) {
        jdbc.update("REPLACE INTO inbox_todo " + TODO_COLUMNS + " VALUES " + TODO_VALUES, todoParams(t));
    }

    @Override
    public void save(EventSource s) {
        jdbc.update("REPLACE INTO event_source " + SOURCE_COLUMNS + " VALUES " + SOURCE_VALUES, sourceParams(s));
    }
}
