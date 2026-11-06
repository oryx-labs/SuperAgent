package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/** ClickHouse credential pools: {@code ReplacingMergeTree(updated_at)}; writes are inserts, reads use FINAL. */
public class ClickHouseProviderCredentialStore extends AbstractJdbcProviderCredentialStore {

    public ClickHouseProviderCredentialStore(JdbcTemplate jdbc) {
        super(jdbc, """
                CREATE TABLE IF NOT EXISTS provider_credential (
                  id String, provider_key String, label String, base_url String, key_cipher String, bearer UInt8,
                  priority Int32, enabled UInt8, status String, last_error String,
                  checked_at Nullable(DateTime64(3, 'UTC')), deleted UInt8,
                  created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
                ) ENGINE = ReplacingMergeTree(updated_at)
                ORDER BY (id)
                """);
    }

    @Override
    protected String readFinal() {
        return " FINAL";
    }

    @Override
    protected String upsert() {
        return "INSERT INTO";
    }
}
