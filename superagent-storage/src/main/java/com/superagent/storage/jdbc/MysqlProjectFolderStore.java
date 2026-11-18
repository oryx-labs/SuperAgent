package com.superagent.storage.jdbc;

import com.superagent.core.file.ProjectFolder;
import com.superagent.core.file.ProjectFolderStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL project folders and file mounts: self-creating tables, upsert via REPLACE. */
public class MysqlProjectFolderStore implements ProjectFolderStore {

    private final JdbcTemplate jdbc;

    public MysqlProjectFolderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS project_folder (
                  company_id VARCHAR(64)  NOT NULL,
                  id         VARCHAR(64)  NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  name       VARCHAR(255) NOT NULL,
                  parent_id  VARCHAR(64)  NOT NULL DEFAULT '',
                  deleted    TINYINT      NOT NULL DEFAULT 0,
                  created_at DATETIME(3)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, id),
                  KEY idx_project_folder_project (company_id, project_id, deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS project_file_placement (
                  company_id VARCHAR(64)  NOT NULL,
                  project_id VARCHAR(64)  NOT NULL,
                  file_id    VARCHAR(64)  NOT NULL,
                  folder_id  VARCHAR(64)  NOT NULL DEFAULT '',
                  placed_by  VARCHAR(16)  NOT NULL,
                  updated_at DATETIME(3)  NOT NULL,
                  PRIMARY KEY (company_id, project_id, file_id),
                  KEY idx_project_file_placement_file (company_id, file_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private final RowMapper<ProjectFolder> mapper = (rs, n) -> {
        String parent = rs.getString("parent_id");
        return new ProjectFolder(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                parent == null || parent.isEmpty() ? null : parent, rs.getInt("deleted") != 0,
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    };

    @Override
    public void save(ProjectFolder f) {
        jdbc.update("REPLACE INTO project_folder (company_id, id, project_id, name, parent_id, deleted, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", TenantSchema.company(), f.id(), f.projectId(), f.name(),
                f.parentId() == null ? "" : f.parentId(), f.deleted() ? 1 : 0, utc(f.createdAt()), utc(f.updatedAt()));
    }

    @Override
    public Optional<ProjectFolder> find(String id) {
        return jdbc.query("SELECT * FROM project_folder WHERE company_id = ? AND id = ? AND deleted = 0", mapper,
                TenantSchema.company(), id).stream().findFirst();
    }

    @Override
    public List<ProjectFolder> list(String projectId) {
        return jdbc.query("SELECT * FROM project_folder WHERE company_id = ? AND project_id = ? AND deleted = 0 ORDER BY name",
                mapper, TenantSchema.company(), projectId);
    }

    @Override
    public Map<String, String> placements(String projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT file_id, folder_id FROM project_file_placement WHERE company_id = ? AND project_id = ? ORDER BY updated_at DESC",
                rs -> { out.put(rs.getString("file_id"), rs.getString("folder_id")); }, TenantSchema.company(), projectId);
        return out;
    }

    @Override
    public List<String> projectsOfFile(String fileId) {
        return jdbc.queryForList("SELECT project_id FROM project_file_placement WHERE company_id = ? AND file_id = ?",
                String.class, TenantSchema.company(), fileId);
    }

    @Override
    public void place(String projectId, String fileId, String folderId, String by) {
        jdbc.update("REPLACE INTO project_file_placement (company_id, project_id, file_id, folder_id, placed_by, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", TenantSchema.company(), projectId, fileId,
                folderId == null ? "" : folderId, by, utc(Instant.now()));
    }

    @Override
    public void unplace(String projectId, String fileId) {
        jdbc.update("DELETE FROM project_file_placement WHERE company_id = ? AND project_id = ? AND file_id = ?",
                TenantSchema.company(), projectId, fileId);
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t == null ? Instant.now() : t, ZoneOffset.UTC);
    }
}
