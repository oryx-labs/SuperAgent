package com.superagent.storage.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL system-setting store: self-creating table, upsert via {@code ON DUPLICATE KEY UPDATE}. */
public class MysqlSystemSettingStore extends AbstractJdbcSystemSettingStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS system_setting (
              setting_key   VARCHAR(128)  NOT NULL,
              setting_value VARCHAR(4096) NOT NULL,
              updated_at    DATETIME(3)   NOT NULL,
              PRIMARY KEY (setting_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlSystemSettingStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
    }

    @Override
    protected String settingFinal() {
        return "";
    }

    @Override
    public void put(String key, String value) {
        jdbc.update("INSERT INTO system_setting (setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE setting_value=VALUES(setting_value), updated_at=VALUES(updated_at)",
                key, nn(value), now());
    }
}
