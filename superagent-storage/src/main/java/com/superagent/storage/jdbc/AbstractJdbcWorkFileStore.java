package com.superagent.storage.jdbc;

import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileKind;
import com.superagent.core.file.WorkFileStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC {@link WorkFileStore} shared by MySQL and ClickHouse: {@code work_file} (one row per file, replaced on
 * every change) and {@code work_file_ref} (which chat reply produced which file). Absent values are stored as
 * "" so both backends use plain string columns; backends differ only in the upsert and FINAL.
 */
abstract class AbstractJdbcWorkFileStore implements WorkFileStore {

    protected static final String COLUMNS = "(id, name, kind, mime_type, size, storage, location, owner, agent_id, "
            + "task_id, origin, deleted, created_at, updated_at)";
    protected static final String VALUES = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected final JdbcTemplate jdbc;

    private final RowMapper<WorkFile> mapper = (rs, i) -> new WorkFile(
            rs.getString("id"), rs.getString("name"), WorkFileKind.parse(rs.getString("kind")),
            emptyToNull(rs.getString("mime_type")), rs.getLong("size"), rs.getString("storage"),
            emptyToNull(rs.getString("location")), rs.getString("owner"), emptyToNull(rs.getString("agent_id")),
            emptyToNull(rs.getString("task_id")), rs.getString("origin"), rs.getInt("deleted") == 1,
            instant(rs, "created_at"), instant(rs, "updated_at"));

    protected AbstractJdbcWorkFileStore(JdbcTemplate jdbc, String... ddl) {
        this.jdbc = jdbc;
        for (String statement : ddl) {
            jdbc.execute(statement);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    /** "REPLACE INTO" for MySQL, "INSERT INTO" for ClickHouse (ReplacingMergeTree keeps the newest row). */
    protected abstract String upsert();

    @Override
    public void save(WorkFile f) {
        jdbc.update(upsert() + " work_file " + COLUMNS + " VALUES " + VALUES,
                f.id(), f.name(), f.kind().name().toLowerCase(), nz(f.mimeType()), f.size(), f.storage(),
                nz(f.location()), f.owner(), nz(f.agentId()), nz(f.taskId()), f.origin(), f.deleted() ? 1 : 0,
                utc(f.createdAt()), utc(f.updatedAt()));
    }

    @Override
    public Optional<WorkFile> find(String id) {
        return jdbc.query("SELECT * FROM work_file" + readFinal() + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    @Override
    public List<WorkFile> listFor(String owner, Collection<String> agentIds) {
        List<Object> args = new ArrayList<>();
        args.add(owner);
        String byAgent = "";
        if (!agentIds.isEmpty()) {
            byAgent = " OR agent_id IN (" + String.join(", ", agentIds.stream().map(a -> "?").toList()) + ")";
            args.addAll(agentIds);
        }
        return jdbc.query("SELECT * FROM work_file" + readFinal() + " WHERE deleted = 0 AND (owner = ?" + byAgent
                + ") ORDER BY updated_at DESC", mapper, args.toArray());
    }

    @Override
    public Optional<WorkFile> delivery(String taskId) {
        return jdbc.query("SELECT * FROM work_file" + readFinal() + " WHERE task_id = ? AND origin = ?",
                mapper, taskId, WorkFile.ORIGIN_DELIVERY).stream().findFirst();
    }

    /** Insert-if-absent, so a reply that revises a file twice keeps its first position in the order. */
    @Override
    public void link(String fileId, String sessionId, String messageId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM work_file_ref" + readFinal()
                + " WHERE file_id = ? AND message_id = ?", Integer.class, fileId, messageId);
        if (n != null && n > 0) {
            return;
        }
        jdbc.update("INSERT INTO work_file_ref (file_id, session_id, message_id, created_at) VALUES (?, ?, ?, ?)",
                fileId, nz(sessionId), messageId, utc(Instant.now()));
    }

    @Override
    public Map<String, List<String>> messages(Collection<String> fileIds) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (fileIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT file_id, message_id FROM work_file_ref" + readFinal() + " WHERE file_id IN ("
                        + String.join(", ", fileIds.stream().map(f -> "?").toList()) + ") ORDER BY created_at",
                rs -> {
                    out.computeIfAbsent(rs.getString("file_id"), k -> new ArrayList<>()).add(rs.getString("message_id"));
                }, fileIds.toArray());
        return out;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
