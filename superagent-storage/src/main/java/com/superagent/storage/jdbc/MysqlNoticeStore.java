package com.superagent.storage.jdbc;

import com.superagent.core.notice.Notice;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL to-do items: self-creating table, upsert via REPLACE. */
public class MysqlNoticeStore extends AbstractJdbcNoticeStore {

    public MysqlNoticeStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS notice (
                  id         VARCHAR(64)   NOT NULL,
                  owner      VARCHAR(128)  NOT NULL,
                  notice_key VARCHAR(200)  NOT NULL,
                  kind       VARCHAR(16)   NOT NULL,
                  state      VARCHAR(16)   NOT NULL,
                  title      VARCHAR(500)  NOT NULL,
                  body       TEXT,
                  actor      VARCHAR(255),
                  link_type  VARCHAR(32),
                  link_id    VARCHAR(128),
                  created_at DATETIME(3)   NOT NULL,
                  updated_at DATETIME(3)   NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_notice_key (owner, notice_key),
                  KEY idx_notice_owner (owner, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    public void save(Notice n) {
        jdbc.update("REPLACE INTO notice " + INSERT, params(n));
    }
}
