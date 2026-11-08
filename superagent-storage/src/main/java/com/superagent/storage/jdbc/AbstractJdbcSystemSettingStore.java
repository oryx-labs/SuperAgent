package com.superagent.storage.jdbc;

import com.superagent.core.settings.SystemSettingStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC {@link SystemSettingStore} shared by MySQL and ClickHouse. Both backends create
 * the table themselves; they differ only in {@link #put} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcSystemSettingStore implements SystemSettingStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcSystemSettingStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String settingFinal();

    @Override
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM system_setting" + settingFinal()
                        + " WHERE company_id = ? ORDER BY setting_key",
                rs -> {
                    out.put(rs.getString("setting_key"), rs.getString("setting_value"));
                }, TenantSchema.company());
        return out;
    }

    protected static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    protected static String nn(String value) {
        return value == null ? "" : value;
    }
}
