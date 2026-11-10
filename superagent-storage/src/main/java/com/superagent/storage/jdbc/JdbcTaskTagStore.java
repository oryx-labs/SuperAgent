package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskTagStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC task tags on MySQL: one row per task holding its tags, newline-separated (a tag never contains one). */
public class JdbcTaskTagStore implements TaskTagStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS task_tag (
              company_id VARCHAR(64) NOT NULL,
              task_id    VARCHAR(64) NOT NULL,
              tags       TEXT        NOT NULL,
              updated_at DATETIME(3) NOT NULL,
              PRIMARY KEY (company_id, task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;


    private final JdbcTemplate jdbc;

    public JdbcTaskTagStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    @Override
    public void set(String taskId, List<String> tags) {
        String joined = String.join("\n", tags);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("REPLACE INTO task_tag (company_id, task_id, tags, updated_at) VALUES (?, ?, ?, ?)",
                TenantSchema.company(), taskId, joined, now);
    }

    @Override
    public List<String> tags(String taskId) {
        return jdbc.queryForList("SELECT tags FROM task_tag WHERE company_id = ? AND task_id = ?",
                String.class, TenantSchema.company(), taskId).stream().findFirst().map(JdbcTaskTagStore::split).orElse(List.of());
    }

    @Override
    public Map<String, List<String>> all() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT task_id, tags FROM task_tag WHERE company_id = ?", rs -> {
            List<String> tags = split(rs.getString(2));
            if (!tags.isEmpty()) {
                out.put(rs.getString(1), tags);
            }
        }, TenantSchema.company());
        return out;
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isBlank() ? List.of()
                : Arrays.stream(joined.split("\n")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }
}
