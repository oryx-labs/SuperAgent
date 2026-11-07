package com.superagent.storage.jdbc;

import com.superagent.core.file.FileFolder;
import com.superagent.core.file.FileFolderStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC {@link FileFolderStore} shared by MySQL and ClickHouse. Nothing is ever deleted: folders carry a flag. */
abstract class AbstractJdbcFileFolderStore implements FileFolderStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcFileFolderStore(JdbcTemplate jdbc, List<String> ddl) {
        this.jdbc = jdbc;
        ddl.forEach(jdbc::execute);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String FOLDER = "(id, owner, name, parent_id, deleted, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    protected static final String PLACEMENT = "(owner, file_id, folder_id, placed_by, updated_at) VALUES (?, ?, ?, ?, ?)";

    protected Object[] params(FileFolder f) {
        return new Object[]{f.id(), f.owner(), f.name(), f.parentId() == null ? "" : f.parentId(), f.deleted() ? 1 : 0,
                utc(f.createdAt()), utc(f.updatedAt())};
    }

    protected static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private final RowMapper<FileFolder> mapper = (rs, n) -> {
        String parent = rs.getString("parent_id");
        return new FileFolder(rs.getString("id"), rs.getString("owner"), rs.getString("name"),
                parent == null || parent.isEmpty() ? null : parent, rs.getInt("deleted") != 0,
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    };

    @Override
    public Optional<FileFolder> find(String id) {
        return jdbc.query("SELECT * FROM file_folder" + readFinal() + " WHERE id = ? AND deleted = 0", mapper, id).stream().findFirst();
    }

    @Override
    public List<FileFolder> list(String owner) {
        return jdbc.query("SELECT * FROM file_folder" + readFinal() + " WHERE owner = ? AND deleted = 0 ORDER BY name", mapper, owner);
    }

    @Override
    public List<String> owners() {
        return jdbc.queryForList("SELECT DISTINCT owner FROM file_folder" + readFinal() + " WHERE deleted = 0", String.class);
    }

    @Override
    public Map<String, String> placements(String owner) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT file_id, folder_id FROM file_placement" + readFinal() + " WHERE owner = ?",
                rs -> { out.put(rs.getString("file_id"), rs.getString("folder_id")); }, owner);
        return out;
    }
}
