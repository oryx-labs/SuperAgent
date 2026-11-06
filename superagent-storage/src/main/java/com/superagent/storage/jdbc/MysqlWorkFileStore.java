package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL work files: self-creating tables, upserts via REPLACE. */
public class MysqlWorkFileStore extends AbstractJdbcWorkFileStore {

    public MysqlWorkFileStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS work_file (
                  id         VARCHAR(64)  NOT NULL,
                  name       VARCHAR(255) NOT NULL,
                  kind       VARCHAR(16)  NOT NULL,
                  mime_type  VARCHAR(128) NOT NULL,
                  size       BIGINT       NOT NULL,
                  storage    VARCHAR(32)  NOT NULL,
                  location   VARCHAR(1024) NOT NULL,
                  owner      VARCHAR(128) NOT NULL,
                  agent_id   VARCHAR(64)  NOT NULL,
                  task_id    VARCHAR(64)  NOT NULL,
                  origin     VARCHAR(16)  NOT NULL,
                  deleted    TINYINT      NOT NULL,
                  created_at DATETIME(3)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (id),
                  KEY idx_work_file_owner (owner, deleted, updated_at),
                  KEY idx_work_file_agent (agent_id, deleted, updated_at),
                  KEY idx_work_file_task (task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS work_file_ref (
                  file_id    VARCHAR(64) NOT NULL,
                  session_id VARCHAR(64) NOT NULL,
                  message_id VARCHAR(64) NOT NULL,
                  created_at DATETIME(3) NOT NULL,
                  PRIMARY KEY (file_id, message_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    protected String upsert() {
        return "REPLACE INTO";
    }
}
