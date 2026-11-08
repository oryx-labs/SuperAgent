package com.superagent.storage.jdbc;

import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectMember;
import com.superagent.core.project.ProjectStatus;
import com.superagent.core.project.ProjectStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** JDBC {@link ProjectStore} shared by MySQL and ClickHouse; backends differ in the upsert and FINAL. */
abstract class AbstractJdbcProjectStore implements ProjectStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcProjectStore(JdbcTemplate jdbc, String projectDdl, String memberDdl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, projectDdl);
        TenantSchema.create(jdbc, memberDdl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String PROJECT_INSERT =
            "(company_id, id, name, description, owner, status, created_at, updated_at, archived_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    protected static final String MEMBER_INSERT =
            "(company_id, project_id, username, added_by, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(Project p) {
        return new Object[]{TenantSchema.company(), p.id(), p.name(), p.description() == null ? "" : p.description(), p.owner(), p.status().name(),
                utc(p.createdAt()), utc(p.updatedAt()), p.archivedAt() == null ? null : utc(p.archivedAt())};
    }

    protected Object[] params(ProjectMember m) {
        return new Object[]{TenantSchema.company(), m.projectId(), m.username(), m.addedBy() == null ? "" : m.addedBy(), m.active() ? 1 : 0,
                utc(m.createdAt()), utc(m.updatedAt())};
    }

    private final RowMapper<Project> projectMapper = (rs, n) -> new Project(rs.getString("id"), rs.getString("name"),
            blank(rs.getString("description")), rs.getString("owner"), ProjectStatus.valueOf(rs.getString("status")),
            instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("updated_at", LocalDateTime.class)),
            instant(rs.getObject("archived_at", LocalDateTime.class)));

    private final RowMapper<ProjectMember> memberMapper = (rs, n) -> new ProjectMember(rs.getString("project_id"),
            rs.getString("username"), blank(rs.getString("added_by")), rs.getInt("active") != 0,
            instant(rs.getObject("created_at", LocalDateTime.class)), instant(rs.getObject("updated_at", LocalDateTime.class)));

    @Override
    public Optional<Project> find(String id) {
        return jdbc.query("SELECT * FROM project" + readFinal() + " WHERE company_id = ? AND id = ?", projectMapper,
                TenantSchema.company(), id).stream().findFirst();
    }

    @Override
    public List<Project> list() {
        return jdbc.query("SELECT * FROM project" + readFinal() + " WHERE company_id = ? ORDER BY updated_at DESC", projectMapper,
                TenantSchema.company());
    }

    @Override
    public List<ProjectMember> members(String projectId) {
        return jdbc.query("SELECT * FROM project_member" + readFinal() + " WHERE company_id = ? AND project_id = ? AND active = 1 ORDER BY created_at",
                memberMapper, TenantSchema.company(), projectId);
    }

    @Override
    public List<String> projectsOf(String username) {
        return jdbc.query("SELECT project_id FROM project_member" + readFinal() + " WHERE company_id = ? AND username = ? AND active = 1",
                (rs, n) -> rs.getString("project_id"), TenantSchema.company(), username);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }
}
