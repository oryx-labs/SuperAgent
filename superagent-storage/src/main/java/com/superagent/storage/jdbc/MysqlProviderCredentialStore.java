package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL credential pools: self-creating table, upserts via REPLACE. */
public class MysqlProviderCredentialStore extends AbstractJdbcProviderCredentialStore {

    public MysqlProviderCredentialStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS provider_credential (
                  id           VARCHAR(64)   NOT NULL,
                  provider_key VARCHAR(32)   NOT NULL,
                  label        VARCHAR(100)  NOT NULL,
                  base_url     VARCHAR(512)  NOT NULL,
                  key_cipher   TEXT          NOT NULL,
                  bearer       TINYINT       NOT NULL,
                  priority     INT           NOT NULL,
                  enabled      TINYINT       NOT NULL,
                  status       VARCHAR(16)   NOT NULL,
                  last_error   VARCHAR(1000) NOT NULL,
                  checked_at   DATETIME(3),
                  deleted      TINYINT       NOT NULL,
                  created_at   DATETIME(3)   NOT NULL,
                  updated_at   DATETIME(3)   NOT NULL,
                  source       VARCHAR(16)   NOT NULL DEFAULT 'console',
                  key_hash     VARCHAR(64)   NOT NULL DEFAULT '',
                  PRIMARY KEY (id),
                  KEY idx_provider_credential (provider_key, deleted, priority)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // Pools from before machine import: add the columns (existing entries were all added on the page).
        for (String[] col : new String[][]{{"source", "VARCHAR(16) NOT NULL DEFAULT 'console'"}, {"key_hash", "VARCHAR(64) NOT NULL DEFAULT ''"}}) {
            Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                    + "AND LOWER(table_name) = 'provider_credential' AND LOWER(column_name) = ?", Integer.class, col[0]);
            if (present == null || present == 0) {
                jdbc.execute("ALTER TABLE provider_credential ADD COLUMN " + col[0] + " " + col[1]);
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
