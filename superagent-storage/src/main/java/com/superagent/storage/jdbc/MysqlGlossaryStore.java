package com.superagent.storage.jdbc;

import com.superagent.core.glossary.GlossaryStore;
import com.superagent.core.glossary.GlossaryTerm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** MySQL glossary terms: a company's own (no project) and each project's, in one self-creating table. */
public class MysqlGlossaryStore implements GlossaryStore {

    private final JdbcTemplate jdbc;

    public MysqlGlossaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS glossary_term (
                  company_id VARCHAR(64)   NOT NULL,
                  id         VARCHAR(64)   NOT NULL,
                  project_id VARCHAR(64),
                  term       VARCHAR(255)  NOT NULL,
                  aliases    TEXT,
                  category   VARCHAR(32),
                  source     VARCHAR(32)   NOT NULL,
                  source_ref VARCHAR(512),
                  created_by VARCHAR(128),
                  created_at DATETIME(3)   NOT NULL,
                  updated_at DATETIME(3)   NOT NULL,
                  PRIMARY KEY (company_id, id),
                  KEY idx_glossary_project (company_id, project_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private final RowMapper<GlossaryTerm> mapper = (rs, n) -> new GlossaryTerm(rs.getString("id"), rs.getString("project_id"),
            rs.getString("term"), aliases(rs.getString("aliases")), rs.getString("category"), rs.getString("source"),
            rs.getString("source_ref"), rs.getString("created_by"), instant(rs.getObject("created_at", LocalDateTime.class)),
            instant(rs.getObject("updated_at", LocalDateTime.class)));

    @Override
    public List<GlossaryTerm> list(String projectId) {
        return projectId == null
                ? jdbc.query("SELECT * FROM glossary_term WHERE company_id = ? AND project_id IS NULL ORDER BY term", mapper,
                TenantSchema.company())
                : jdbc.query("SELECT * FROM glossary_term WHERE company_id = ? AND project_id = ? ORDER BY term", mapper,
                TenantSchema.company(), projectId);
    }

    @Override
    public Optional<GlossaryTerm> find(String id) {
        return jdbc.query("SELECT * FROM glossary_term WHERE company_id = ? AND id = ?", mapper, TenantSchema.company(), id)
                .stream().findFirst();
    }

    @Override
    public void save(GlossaryTerm t) {
        jdbc.update("REPLACE INTO glossary_term (company_id, id, project_id, term, aliases, category, source, source_ref,"
                        + " created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), t.id(), t.projectId(), t.term(), lines(t.aliases()), t.category(), t.source(),
                t.sourceRef(), t.createdBy(), utc(t.createdAt()), utc(t.updatedAt()));
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM glossary_term WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }

    /** One alias a line (an alias never holds a line break: the service strips them). */
    private static String lines(List<String> aliases) {
        return aliases == null || aliases.isEmpty() ? null : String.join("\n", aliases);
    }

    private static List<String> aliases(String raw) {
        return raw == null || raw.isBlank() ? List.of() : List.of(raw.split("\n"));
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i == null ? Instant.now() : i, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
