package com.superagent.storage.jdbc;

import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link ModelProviderStore} shared by MySQL and ClickHouse. Both backends
 * create the table themselves; they differ only in {@link #save} and whether reads
 * need {@code FINAL}.
 */
abstract class AbstractJdbcModelProviderStore implements ModelProviderStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcModelProviderStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String providerFinal();

    private final RowMapper<ModelProvider> mapper = (rs, n) -> new ModelProvider(
            rs.getString("id"),
            rs.getString("provider_key"),
            rs.getBoolean("installed"),
            rs.getBoolean("can_infer"),
            emptyToNull(rs.getString("current_version")),
            emptyToNull(rs.getString("latest_version")),
            rs.getString("status"),
            instant(rs, "last_checked_at"),
            emptyToNull(rs.getString("models_json")),
            emptyToNull(rs.getString("models_error")),
            instant(rs, "models_checked_at"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    @Override
    public List<ModelProvider> list() {
        return jdbc.query("SELECT * FROM model_provider" + providerFinal() + " ORDER BY created_at", mapper);
    }

    @Override
    public Optional<ModelProvider> findById(String id) {
        return jdbc.query("SELECT * FROM model_provider" + providerFinal() + " WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ModelProvider> findByKey(String providerKey) {
        return jdbc.query("SELECT * FROM model_provider" + providerFinal() + " WHERE provider_key = ?",
                mapper, providerKey).stream().findFirst();
    }

    protected static final String COLUMNS =
            "(id, provider_key, installed, can_infer, current_version, latest_version, status, "
                    + "last_checked_at, models_json, models_error, models_checked_at, created_at, updated_at)";

    protected Object[] params(ModelProvider p) {
        return new Object[]{
                p.id(), p.providerKey(), p.installed(), p.canInfer(), nn(p.currentVersion()), nn(p.latestVersion()),
                p.status(), utc(p.lastCheckedAt()), nn(p.modelsJson()), nn(p.modelsError()),
                utc(p.modelsCheckedAt()), utc(p.createdAt()), utc(p.updatedAt())};
    }

    protected void insert(ModelProvider p) {
        jdbc.update("INSERT INTO model_provider " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                params(p));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    protected static String nn(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
