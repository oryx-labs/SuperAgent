package com.superagent.storage.jdbc;

import com.superagent.core.provider.ProviderCredential;
import com.superagent.core.provider.ProviderCredentialStore;
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
 * JDBC {@link ProviderCredentialStore} shared by MySQL and ClickHouse. A delete is a {@code deleted} row (an
 * upsert on both backends); absent values are stored as "" so both use plain string columns.
 */
abstract class AbstractJdbcProviderCredentialStore implements ProviderCredentialStore {

    private static final String COLUMNS = "(id, provider_key, label, base_url, key_cipher, bearer, priority, enabled, "
            + "status, last_error, checked_at, deleted, created_at, updated_at)";

    protected final JdbcTemplate jdbc;

    private final RowMapper<ProviderCredential> mapper = (rs, i) -> new ProviderCredential(
            rs.getString("id"), rs.getString("provider_key"), rs.getString("label"), emptyToNull(rs.getString("base_url")),
            rs.getString("key_cipher"), rs.getInt("bearer") == 1, rs.getInt("priority"), rs.getInt("enabled") == 1,
            rs.getString("status"), emptyToNull(rs.getString("last_error")), instant(rs, "checked_at"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    protected AbstractJdbcProviderCredentialStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    /** "REPLACE INTO" for MySQL, "INSERT INTO" for ClickHouse (ReplacingMergeTree keeps the newest row). */
    protected abstract String upsert();

    @Override
    public List<ProviderCredential> list(String providerKey) {
        return jdbc.query("SELECT * FROM provider_credential" + readFinal()
                + " WHERE provider_key = ? AND deleted = 0 ORDER BY priority, created_at", mapper, providerKey);
    }

    @Override
    public Optional<ProviderCredential> find(String id) {
        return jdbc.query("SELECT * FROM provider_credential" + readFinal() + " WHERE id = ? AND deleted = 0",
                mapper, id).stream().findFirst();
    }

    @Override
    public void save(ProviderCredential c) {
        write(c, false);
    }

    @Override
    public void delete(String id) {
        find(id).ifPresent(c -> write(c, true));
    }

    private void write(ProviderCredential c, boolean deleted) {
        jdbc.update(upsert() + " provider_credential " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.providerKey(), c.label(), nz(c.baseUrl()), c.keyCipher(), c.bearer() ? 1 : 0, c.priority(),
                c.enabled() ? 1 : 0, c.status(), nz(c.lastError()), utc(c.checkedAt()), deleted ? 1 : 0,
                utc(c.createdAt()), utc(deleted ? Instant.now() : c.updatedAt()));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
