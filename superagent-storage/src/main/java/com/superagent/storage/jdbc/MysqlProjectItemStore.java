package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL project items: self-creating table, upsert via REPLACE. */
public class MysqlProjectItemStore extends AbstractJdbcProjectItemStore {

    public MysqlProjectItemStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS project_item (
                  company_id VARCHAR(64)  NOT NULL,
                  kind       VARCHAR(16)  NOT NULL,
                  item_id    VARCHAR(64)  NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, kind, item_id),
                  KEY idx_project_item_project (company_id, project_id, kind)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    protected String readFinal() {
        return "";
    }

    @Override
    protected String upsert() {
        return "REPLACE";
    }
}
