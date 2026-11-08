package com.superagent.storage.jdbc;

import com.superagent.core.timing.TurnTiming;
import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse turn timings: {@code ReplacingMergeTree(finished_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseTurnTimingStore extends AbstractJdbcTurnTimingStore {

    public ClickHouseTurnTimingStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS turn_timing (
                  company_id String, message_id String, session_id String, label String,
                  started_at DateTime64(3, 'UTC'), finished_at DateTime64(3, 'UTC'),
                  prep_ms Int64, startup_ms Int64, model_ms Int64, tools_ms Int64, output_ms Int64, retry_ms Int64,
                  save_ms Int64, attempts Int32, tools String
                ) ENGINE = ReplacingMergeTree(finished_at)
                ORDER BY (company_id, message_id)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(TurnTiming t) {
        jdbc.update("INSERT INTO turn_timing " + INSERT, params(t));
    }
}
