package com.superagent.storage.jdbc;

import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.project.ProjectItemStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link ProjectItemStore}: one row per item, keyed by kind + item id, so an item is in at most one project.
 * Taking an item out keeps the row with an empty project id (an insert on ClickHouse, like every other write there).
 */
abstract class AbstractJdbcProjectItemStore implements ProjectItemStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcProjectItemStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    /** REPLACE for MySQL, INSERT for ClickHouse. */
    protected abstract String upsert();

    @Override
    public void assign(ProjectItemKind kind, String itemId, String projectId) {
        write(kind, itemId, projectId == null ? "" : projectId);
    }

    @Override
    public void unassign(ProjectItemKind kind, String itemId) {
        write(kind, itemId, "");
    }

    private void write(ProjectItemKind kind, String itemId, String projectId) {
        jdbc.update(upsert() + " INTO project_item (company_id, kind, item_id, project_id, updated_at) VALUES (?, ?, ?, ?, ?)",
                TenantSchema.company(), kind.name(), itemId, projectId, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }

    @Override
    public Optional<String> projectOf(ProjectItemKind kind, String itemId) {
        return jdbc.query("SELECT project_id FROM project_item" + readFinal() + " WHERE company_id = ? AND kind = ? AND item_id = ?",
                        (rs, n) -> rs.getString("project_id"), TenantSchema.company(), kind.name(), itemId).stream()
                .filter(p -> p != null && !p.isEmpty()).findFirst();
    }

    @Override
    public List<String> items(ProjectItemKind kind, String projectId) {
        return jdbc.query("SELECT item_id FROM project_item" + readFinal() + " WHERE company_id = ? AND kind = ? AND project_id = ? ORDER BY updated_at DESC",
                (rs, n) -> rs.getString("item_id"), TenantSchema.company(), kind.name(), projectId);
    }
}
