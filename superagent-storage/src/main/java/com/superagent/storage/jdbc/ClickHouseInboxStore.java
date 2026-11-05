package com.superagent.storage.jdbc;

import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxTodo;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/** ClickHouse inbox stores: {@code ReplacingMergeTree(updated_at)} tables; writes are inserts, reads use FINAL. */
public class ClickHouseInboxStore extends AbstractJdbcInboxStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS inbox_event (
              id String, owner String, source String, external_id String, chat_id String, chat_name String,
              chat_type String, thread_id String, sender_id String, sender_name String, text String,
              mentions_me UInt8, raw String, priority String, triage_reason String, processed UInt8, handling String,
              occurred_at Nullable(DateTime64(3, 'UTC')), received_at DateTime64(3, 'UTC'),
              updated_at DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY id
            """,
            """
            CREATE TABLE IF NOT EXISTS inbox_todo (
              id String, owner String, title String, detail String, requester String, due String,
              event_ids String, source String, status String, task_id String,
              created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY id
            """,
            """
            CREATE TABLE IF NOT EXISTS event_source (
              owner String, type String, app_id String, secret_cipher String, enabled UInt8,
              owner_external_id String, bind_code String, vip_names String, urgent_keywords String,
              digest_time String, timezone String, secretary_agent_id String, last_digest_date String,
              last_hourly_at DateTime64(3, 'UTC'), status String, last_error String,
              created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (owner, type)
            """,
            "ALTER TABLE inbox_event ADD COLUMN IF NOT EXISTS handling String");

    public ClickHouseInboxStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public boolean insertIfAbsent(InboxEvent e) {
        if (eventExists(e.id())) {
            return false;
        }
        update(e);
        return true;
    }

    @Override
    public void update(InboxEvent e) {
        jdbc.update("INSERT INTO inbox_event " + EVENT_COLUMNS + " VALUES " + EVENT_VALUES, eventParams(e));
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event FINAL WHERE received_at < ?", Integer.class, utc(cutoff));
        jdbc.update("ALTER TABLE inbox_event DELETE WHERE received_at < ?", utc(cutoff));
        return n == null ? 0 : n;
    }

    @Override
    public void save(InboxTodo t) {
        jdbc.update("INSERT INTO inbox_todo " + TODO_COLUMNS + " VALUES " + TODO_VALUES, todoParams(t));
    }

    @Override
    public void save(EventSource s) {
        jdbc.update("INSERT INTO event_source " + SOURCE_COLUMNS + " VALUES " + SOURCE_VALUES, sourceParams(s));
    }
}
