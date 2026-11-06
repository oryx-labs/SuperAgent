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
                  PRIMARY KEY (id),
                  KEY idx_provider_credential (provider_key, deleted, priority)
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
