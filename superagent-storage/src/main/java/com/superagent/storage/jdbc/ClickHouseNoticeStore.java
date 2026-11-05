package com.superagent.storage.jdbc;

import com.superagent.core.notice.Notice;
import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse to-do items: {@code ReplacingMergeTree(updated_at)} keyed by id; writes are inserts, reads use FINAL. */
public class ClickHouseNoticeStore extends AbstractJdbcNoticeStore {

    public ClickHouseNoticeStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS notice (
                  id String, owner String, notice_key String, kind String, state String, title String, body String,
                  actor String, link_type String, link_id String,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY id
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    public void save(Notice n) {
        jdbc.update("INSERT INTO notice " + INSERT, params(n));
    }
}
