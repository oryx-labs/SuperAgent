package com.superagent.storage.jdbc;

import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JDBC {@link UserStore} shared by MySQL and ClickHouse. Roles are stored as a
 * comma-separated string. Backends differ only in {@link #save} and whether reads
 * need {@code FINAL}.
 */
abstract class AbstractJdbcUserStore implements UserStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcUserStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String userFinal();

    private final RowMapper<User> mapper = (rs, n) -> new User(
            rs.getString("id"),
            rs.getString("username"),
            emptyToNull(rs.getString("display_name")),
            parseRoles(rs.getString("roles")),
            rs.getBoolean("enabled"),
            emptyToNull(rs.getString("password_hash")),
            emptyToNull(rs.getString("phone")),
            emptyToNull(rs.getString("external_id")),
            UserSource.valueOf(rs.getString("source")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    @Override
    public List<User> listUsers() {
        return jdbc.query("SELECT * FROM app_user" + userFinal() + " WHERE company_id = ? ORDER BY created_at", mapper,
                TenantSchema.company());
    }

    @Override
    public Optional<User> findById(String id) {
        return one("SELECT * FROM app_user" + userFinal() + " WHERE company_id = ? AND id = ?", id);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return one("SELECT * FROM app_user" + userFinal() + " WHERE company_id = ? AND username = ?", username);
    }

    @Override
    public Optional<String> companyOfUsername(String username) {
        return jdbc.queryForList("SELECT company_id FROM app_user" + userFinal() + " WHERE username = ?",
                String.class, username).stream().findFirst();
    }

    @Override
    public Optional<User> findByPhone(String phone) {
        return phone == null ? Optional.empty()
                : one("SELECT * FROM app_user" + userFinal() + " WHERE company_id = ? AND phone = ?", phone);
    }

    @Override
    public Optional<User> findByExternalId(String externalId) {
        return externalId == null ? Optional.empty()
                : one("SELECT * FROM app_user" + userFinal() + " WHERE company_id = ? AND external_id = ?", externalId);
    }

    private Optional<User> one(String sql, Object arg) {
        return jdbc.query(sql, mapper, TenantSchema.company(), arg).stream().findFirst();
    }

    protected static final String COLUMNS =
            "(company_id, id, username, display_name, roles, enabled, password_hash, phone, external_id, source, "
                    + "created_at, updated_at)";

    protected Object[] params(User u) {
        return new Object[]{
                TenantSchema.company(), u.id(), u.username(), nn(u.displayName()), String.join(",", u.roles()), u.enabled(),
                nn(u.passwordHash()), nn(u.phone()), nn(u.externalId()), u.source().name(),
                utc(u.createdAt()), utc(u.updatedAt())};
    }

    protected void insert(User u) {
        jdbc.update("INSERT INTO app_user " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", params(u));
    }

    private static Set<String> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
