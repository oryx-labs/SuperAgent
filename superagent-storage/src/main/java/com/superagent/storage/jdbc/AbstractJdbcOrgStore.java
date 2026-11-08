package com.superagent.storage.jdbc;

import com.superagent.core.org.OrgStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC {@link OrgStore} shared by MySQL and ClickHouse: {@code user_manager} (one row per user with a
 * manager) and {@code user_team} (one row per membership). Backends differ in how rows are replaced
 * and deleted, and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcOrgStore implements OrgStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcOrgStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String managerFinal();

    /** Deletes rows of {@code table} matching {@code where} (which carries the company condition). */
    protected abstract void deleteWhere(String table, String where, Object... args);

    @Override
    public Optional<String> manager(String username) {
        return jdbc.queryForList("SELECT manager FROM user_manager" + managerFinal() + " WHERE company_id = ? AND username = ?",
                String.class, TenantSchema.company(), username).stream().filter(m -> m != null && !m.isEmpty()).findFirst();
    }

    @Override
    public Map<String, String> managers() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT username, manager FROM user_manager" + managerFinal() + " WHERE company_id = ?", rs -> {
            String m = rs.getString("manager");
            if (m != null && !m.isEmpty()) {
                out.put(rs.getString("username"), m);
            }
        }, TenantSchema.company());
        return out;
    }

    @Override
    public void setManager(String username, String manager) {
        String company = TenantSchema.company();
        deleteWhere("user_manager", "company_id = ? AND username = ?", company, username);
        if (manager != null && !manager.isBlank()) {
            jdbc.update("INSERT INTO user_manager (company_id, username, manager, updated_at) VALUES (?, ?, ?, ?)",
                    company, username, manager, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        }
    }

    @Override
    public List<String> teams(String username) {
        return jdbc.queryForList("SELECT team_path FROM user_team WHERE company_id = ? AND username = ? ORDER BY team_path",
                String.class, TenantSchema.company(), username);
    }

    @Override
    public Map<String, List<String>> memberships() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT username, team_path FROM user_team WHERE company_id = ? ORDER BY username, team_path",
                rs -> { out.computeIfAbsent(rs.getString("username"), k -> new ArrayList<>()).add(rs.getString("team_path")); },
                TenantSchema.company());
        return out;
    }

    @Override
    public void setTeams(String username, List<String> teams) {
        String company = TenantSchema.company();
        deleteWhere("user_team", "company_id = ? AND username = ?", company, username);
        for (String team : teams) {
            jdbc.update("INSERT INTO user_team (company_id, username, team_path) VALUES (?, ?, ?)", company, username, team);
        }
    }
}
