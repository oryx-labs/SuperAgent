package com.superagent.storage.jdbc;

import com.superagent.core.project.ProjectBrief;
import com.superagent.core.project.ProjectContextStore;
import com.superagent.core.project.ProjectMemory;
import com.superagent.core.project.ProjectRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** MySQL project repositories, briefs and memory: self-creating tables, upsert via REPLACE. */
public class MysqlProjectContextStore implements ProjectContextStore {

    private final JdbcTemplate jdbc;

    public MysqlProjectContextStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS project_repo (
                  company_id     VARCHAR(64)   NOT NULL,
                  id             VARCHAR(64)   NOT NULL,
                  project_id     VARCHAR(64)   NOT NULL,
                  name           VARCHAR(255)  NOT NULL,
                  location       VARCHAR(1024) NOT NULL,
                  default_branch VARCHAR(255),
                  delivery       VARCHAR(16)   NOT NULL,
                  purpose        TEXT,
                  position       INT           NOT NULL,
                  created_at     DATETIME(3)   NOT NULL,
                  updated_at     DATETIME(3)   NOT NULL,
                  PRIMARY KEY (company_id, id),
                  KEY idx_project_repo_project (company_id, project_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS project_brief (
                  company_id VARCHAR(64)  NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  goal       TEXT,
                  background TEXT,
                  norms      TEXT,
                  glossary   TEXT,
                  updated_by VARCHAR(128),
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, project_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS project_memory (
                  company_id VARCHAR(64)  NOT NULL,
                  id         VARCHAR(64)  NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  title      VARCHAR(255) NOT NULL,
                  content    TEXT,
                  agent_id   VARCHAR(64),
                  session_id VARCHAR(64),
                  task_id    VARCHAR(64),
                  created_by VARCHAR(128),
                  updated_by VARCHAR(128),
                  deleted    TINYINT      NOT NULL DEFAULT 0,
                  created_at DATETIME(3)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, id),
                  KEY idx_project_memory_project (company_id, project_id, deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private final RowMapper<ProjectRepo> repoMapper = (rs, n) -> new ProjectRepo(rs.getString("id"), rs.getString("project_id"),
            rs.getString("name"), rs.getString("location"), rs.getString("default_branch"), rs.getString("delivery"),
            rs.getString("purpose"), rs.getInt("position"), instant(rs.getObject("created_at", LocalDateTime.class)),
            instant(rs.getObject("updated_at", LocalDateTime.class)));

    private final RowMapper<ProjectBrief> briefMapper = (rs, n) -> new ProjectBrief(rs.getString("project_id"),
            rs.getString("goal"), rs.getString("background"), rs.getString("norms"), rs.getString("glossary"),
            rs.getString("updated_by"), instant(rs.getObject("updated_at", LocalDateTime.class)));

    private final RowMapper<ProjectMemory> memoryMapper = (rs, n) -> new ProjectMemory(rs.getString("id"),
            rs.getString("project_id"), rs.getString("title"), rs.getString("content"), rs.getString("agent_id"),
            rs.getString("session_id"), rs.getString("task_id"), rs.getString("created_by"), rs.getString("updated_by"),
            rs.getInt("deleted") != 0, instant(rs.getObject("created_at", LocalDateTime.class)),
            instant(rs.getObject("updated_at", LocalDateTime.class)));

    @Override
    public List<ProjectRepo> repos(String projectId) {
        return jdbc.query("SELECT * FROM project_repo WHERE company_id = ? AND project_id = ? ORDER BY position, created_at",
                repoMapper, TenantSchema.company(), projectId);
    }

    @Override
    public void saveRepo(ProjectRepo r) {
        jdbc.update("REPLACE INTO project_repo (company_id, id, project_id, name, location, default_branch, delivery, purpose,"
                        + " position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), r.id(), r.projectId(), r.name(), r.location(), r.defaultBranch(), r.delivery(),
                r.purpose(), r.position(), utc(r.createdAt()), utc(r.updatedAt()));
    }

    @Override
    public void deleteRepo(String projectId, String repoId) {
        jdbc.update("DELETE FROM project_repo WHERE company_id = ? AND project_id = ? AND id = ?",
                TenantSchema.company(), projectId, repoId);
    }

    @Override
    public Optional<ProjectBrief> brief(String projectId) {
        return jdbc.query("SELECT * FROM project_brief WHERE company_id = ? AND project_id = ?", briefMapper,
                TenantSchema.company(), projectId).stream().findFirst();
    }

    @Override
    public void saveBrief(ProjectBrief b) {
        jdbc.update("REPLACE INTO project_brief (company_id, project_id, goal, background, norms, glossary, updated_by, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", TenantSchema.company(), b.projectId(), b.goal(), b.background(),
                b.norms(), b.glossary(), b.updatedBy(), utc(b.updatedAt()));
    }

    @Override
    public List<ProjectMemory> memories(String projectId) {
        return jdbc.query("SELECT * FROM project_memory WHERE company_id = ? AND project_id = ? AND deleted = 0"
                + " ORDER BY updated_at DESC", memoryMapper, TenantSchema.company(), projectId);
    }

    @Override
    public Optional<ProjectMemory> memory(String id) {
        return jdbc.query("SELECT * FROM project_memory WHERE company_id = ? AND id = ? AND deleted = 0", memoryMapper,
                TenantSchema.company(), id).stream().findFirst();
    }

    @Override
    public void saveMemory(ProjectMemory m) {
        jdbc.update("REPLACE INTO project_memory (company_id, id, project_id, title, content, agent_id, session_id, task_id,"
                        + " created_by, updated_by, deleted, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), m.id(), m.projectId(), m.title(), m.content(), m.agentId(), m.sessionId(), m.taskId(),
                m.createdBy(), m.updatedBy(), m.deleted() ? 1 : 0, utc(m.createdAt()), utc(m.updatedAt()));
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }
}
