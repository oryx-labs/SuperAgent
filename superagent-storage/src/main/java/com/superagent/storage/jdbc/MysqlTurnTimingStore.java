package com.superagent.storage.jdbc;

import com.superagent.core.timing.TurnTiming;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL turn timings: self-creating table, upsert via REPLACE. */
public class MysqlTurnTimingStore extends AbstractJdbcTurnTimingStore {

    public MysqlTurnTimingStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS turn_timing (
                  message_id  VARCHAR(64)  NOT NULL,
                  session_id  VARCHAR(64)  NOT NULL,
                  label       VARCHAR(32)  NOT NULL DEFAULT '',
                  started_at  DATETIME(3)  NOT NULL,
                  finished_at DATETIME(3)  NOT NULL,
                  prep_ms     BIGINT       NOT NULL,
                  startup_ms  BIGINT       NOT NULL,
                  model_ms    BIGINT       NOT NULL,
                  tools_ms    BIGINT       NOT NULL,
                  output_ms   BIGINT       NOT NULL,
                  retry_ms    BIGINT       NOT NULL,
                  save_ms     BIGINT       NOT NULL,
                  attempts    INT          NOT NULL,
                  tools       TEXT,
                  PRIMARY KEY (message_id),
                  KEY idx_turn_timing_session (session_id, started_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(TurnTiming t) {
        jdbc.update("REPLACE INTO turn_timing " + INSERT, params(t));
    }
}
