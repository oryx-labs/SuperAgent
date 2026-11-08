package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * ClickHouse system-setting store: self-creating {@code ReplacingMergeTree(updated_at)}
 * table keyed by (company, setting key); reads use {@code FINAL}, a put is a plain insert.
 */
public class ClickHouseSystemSettingStore extends AbstractJdbcSystemSettingStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS system_setting (
              company_id    String,
              setting_key   String,
              setting_value String,
              updated_at    DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at)
            ORDER BY (company_id, setting_key)
            """);

    public ClickHouseSystemSettingStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String settingFinal() {
        return " FINAL";
    }

    @Override
    public void put(String key, String value) {
        jdbc.update("INSERT INTO system_setting (company_id, setting_key, setting_value, updated_at) VALUES (?, ?, ?, ?)",
                TenantSchema.company(), key, nn(value), now());
    }
}
