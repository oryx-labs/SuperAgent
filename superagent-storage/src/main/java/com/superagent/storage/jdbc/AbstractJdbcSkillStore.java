package com.superagent.storage.jdbc;

import com.superagent.core.skill.Skill;
import com.superagent.core.skill.SkillStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link SkillStore} shared by MySQL and ClickHouse. Bindings are one row per owner holding
 * the comma-separated skill ids (ids are UUIDs, so no escaping), which keeps "replace the whole
 * list" a single upsert on both backends. Backends differ only in upserts and {@code FINAL}.
 */
abstract class AbstractJdbcSkillStore implements SkillStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcSkillStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String COLUMNS =
            "(company_id, id, name, description, category, content, space, enabled, deleted, created_by, updated_by, created_at, updated_at)";

    private final RowMapper<Skill> mapper = (rs, n) -> new Skill(
            rs.getString("id"), rs.getString("name"), emptyToNull(rs.getString("description")),
            emptyToNull(rs.getString("category")), nn(rs.getString("content")), emptyToNull(rs.getString("space")),
            rs.getInt("enabled") != 0, rs.getInt("deleted") != 0,
            emptyToNull(rs.getString("created_by")), emptyToNull(rs.getString("updated_by")),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    protected Object[] params(Skill s) {
        return new Object[]{TenantSchema.company(), s.id(), nn(s.name()), nn(s.description()), nn(s.category()), nn(s.content()), nn(s.space()),
                s.enabled() ? 1 : 0, s.deleted() ? 1 : 0, nn(s.createdBy()), nn(s.updatedBy()),
                utc(s.createdAt()), utc(s.updatedAt())};
    }

    @Override
    public List<Skill> list() {
        return jdbc.query("SELECT * FROM skill" + readFinal() + " WHERE company_id = ? ORDER BY name", mapper,
                TenantSchema.company());
    }

    @Override
    public Optional<Skill> find(String id) {
        return jdbc.query("SELECT * FROM skill" + readFinal() + " WHERE company_id = ? AND id = ?", mapper,
                TenantSchema.company(), id).stream().findFirst();
    }

    @Override
    public Optional<List<String>> binding(String ownerType, String ownerId) {
        return jdbc.query("SELECT skill_ids FROM skill_binding" + readFinal() + " WHERE company_id = ? AND owner_type = ? AND owner_id = ?",
                        (rs, n) -> nn(rs.getString("skill_ids")), TenantSchema.company(), ownerType, ownerId)
                .stream().findFirst()
                .map(csv -> csv.isEmpty() ? List.of() : Arrays.stream(csv.split(",")).toList());
    }

    protected static String csv(List<String> ids) {
        return String.join(",", ids);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    protected static LocalDateTime now() {
        return utc(Instant.now());
    }

    protected static String nn(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
