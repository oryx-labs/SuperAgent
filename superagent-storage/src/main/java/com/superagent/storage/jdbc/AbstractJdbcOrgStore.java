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
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String managerFinal();

    /** Deletes rows of {@code table} matching {@code where}. */
    protected abstract void deleteWhere(String table, String where, Object... args);

    @Override
    public Optional<String> manager(String username) {
        return jdbc.queryForList("SELECT manager FROM user_manager" + managerFinal() + " WHERE username = ?",
                String.class, username).stream().filter(m -> m != null && !m.isEmpty()).findFirst();
    }

    @Override
    public Map<String, String> managers() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT username, manager FROM user_manager" + managerFinal(), rs -> {
            String m = rs.getString("manager");
            if (m != null && !m.isEmpty()) {
                out.put(rs.getString("username"), m);
            }
        });
        return out;
    }

    @Override
    public void setManager(String username, String manager) {
        deleteWhere("user_manager", "username = ?", username);
        if (manager != null && !manager.isBlank()) {
            jdbc.update("INSERT INTO user_manager (username, manager, updated_at) VALUES (?, ?, ?)",
                    username, manager, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        }
    }

    @Override
    public List<String> teams(String username) {
        return jdbc.queryForList("SELECT team_path FROM user_team WHERE username = ? ORDER BY team_path",
                String.class, username);
    }

    @Override
    public Map<String, List<String>> memberships() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT username, team_path FROM user_team ORDER BY username, team_path",
                rs -> { out.computeIfAbsent(rs.getString("username"), k -> new ArrayList<>()).add(rs.getString("team_path")); });
        return out;
    }

    @Override
    public void setTeams(String username, List<String> teams) {
        deleteWhere("user_team", "username = ?", username);
        for (String team : teams) {
            jdbc.update("INSERT INTO user_team (username, team_path) VALUES (?, ?)", username, team);
        }
    }
}
