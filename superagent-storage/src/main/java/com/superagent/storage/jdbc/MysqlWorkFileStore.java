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
                  tags       VARCHAR(512) NOT NULL DEFAULT '',
                  tag_state  VARCHAR(8)   NOT NULL DEFAULT '',
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
        // Tables from before tags: add the columns; existing files start untagged, so the tagger fills them in.
        for (String[] col : new String[][]{{"tags", "VARCHAR(512) NOT NULL DEFAULT ''"}, {"tag_state", "VARCHAR(8) NOT NULL DEFAULT ''"}}) {
            Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                    + "AND LOWER(table_name) = 'work_file' AND LOWER(column_name) = ?", Integer.class, col[0]);
            if (present == null || present == 0) {
                jdbc.execute("ALTER TABLE work_file ADD COLUMN " + col[0] + " " + col[1]);
            }
        }
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
