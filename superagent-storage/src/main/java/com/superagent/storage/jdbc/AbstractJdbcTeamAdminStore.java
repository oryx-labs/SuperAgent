package com.superagent.storage.jdbc;

import com.superagent.core.org.TeamAdminStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JDBC {@link TeamAdminStore} shared by MySQL and ClickHouse. A removal is an inactive row (an upsert on both
 * backends); backends differ only in the upsert and FINAL.
 */
abstract class AbstractJdbcTeamAdminStore implements TeamAdminStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcTeamAdminStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected abstract void upsert(String team, String username, boolean active, LocalDateTime at);

    @Override
    public Map<String, List<String>> all() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        jdbc.query("SELECT team_path, username FROM team_admin" + readFinal() + " WHERE active = 1 ORDER BY team_path, username",
                rs -> {
                    out.computeIfAbsent(rs.getString("team_path"), k -> new ArrayList<>()).add(rs.getString("username"));
                });
        return out;
    }

    @Override
    public void set(String team, List<String> usernames) {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        Set<String> next = new LinkedHashSet<>(usernames);
        for (String current : all().getOrDefault(team, List.of())) {
            if (!next.contains(current)) {
                upsert(team, current, false, now);
            }
        }
        for (String u : next) {
            upsert(team, u, true, now);
        }
    }
}
