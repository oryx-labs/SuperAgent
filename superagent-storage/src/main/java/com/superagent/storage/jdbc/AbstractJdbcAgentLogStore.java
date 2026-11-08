package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentLogEntry;
import com.superagent.core.agent.AgentLogStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * JDBC {@link AgentLogStore} shared by MySQL and ClickHouse. Both create the table themselves; they differ
 * only in how rows are deleted. The per-agent cap is enforced by finding the timestamp of each over-full
 * agent's oldest kept line and deleting what's older.
 */
abstract class AbstractJdbcAgentLogStore implements AgentLogStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcAgentLogStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** Deletes rows matching {@code where} (MySQL DELETE vs ClickHouse ALTER ... DELETE). */
    protected abstract void deleteWhere(String where, Object... args);

    private void deleteInCompany(String where, Object... args) {
        Object[] scoped = new Object[args.length + 1];
        scoped[0] = TenantSchema.company();
        System.arraycopy(args, 0, scoped, 1, args.length);
        deleteWhere("company_id = ? AND " + where, scoped);
    }

    private final RowMapper<AgentLogEntry> mapper = (rs, n) -> new AgentLogEntry(
            rs.getString("id"),
            rs.getString("agent_id"),
            rs.getString("owner"),
            instant(rs, "at_time"),
            rs.getString("level"),
            rs.getString("category"),
            rs.getString("summary"),
            emptyToNull(rs.getString("detail")));

    @Override
    public void append(AgentLogEntry e) {
        jdbc.update("INSERT INTO agent_log (company_id, id, agent_id, owner, at_time, level, category, summary, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), e.id(), e.agentId(), e.owner() == null ? "" : e.owner(), utc(e.at()), e.level(), e.category(),
                e.summary(), e.detail() == null ? "" : e.detail());
    }

    @Override
    public List<AgentLogEntry> list(String agentId, String category, int limit) {
        if (category == null || category.isBlank()) {
            return jdbc.query("SELECT * FROM agent_log WHERE company_id = ? AND agent_id = ? ORDER BY at_time DESC LIMIT ?",
                    mapper, TenantSchema.company(), agentId, limit);
        }
        return jdbc.query("SELECT * FROM agent_log WHERE company_id = ? AND agent_id = ? AND category = ? ORDER BY at_time DESC LIMIT ?",
                mapper, TenantSchema.company(), agentId, category, limit);
    }

    @Override
    public void prune(Instant before, int keepPerAgent) {
        String company = TenantSchema.company();
        deleteInCompany("at_time < ?", utc(before));
        List<String> overFull = jdbc.queryForList(
                "SELECT agent_id FROM agent_log WHERE company_id = ? GROUP BY agent_id HAVING COUNT(*) > ?", String.class,
                company, keepPerAgent);
        for (String agentId : overFull) {
            List<LocalDateTime> cutoff = jdbc.queryForList(
                    "SELECT at_time FROM agent_log WHERE company_id = ? AND agent_id = ? ORDER BY at_time DESC LIMIT 1 OFFSET ?",
                    LocalDateTime.class, company, agentId, keepPerAgent - 1);
            if (!cutoff.isEmpty()) {
                deleteInCompany("agent_id = ? AND at_time < ?", agentId, cutoff.get(0));
            }
        }
    }

    @Override
    public void deleteAgent(String agentId) {
        deleteInCompany("agent_id = ?", agentId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
