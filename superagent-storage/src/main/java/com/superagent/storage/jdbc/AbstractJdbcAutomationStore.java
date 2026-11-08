package com.superagent.storage.jdbc;

import com.superagent.core.automation.Automation;
import com.superagent.core.automation.AutomationRun;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.automation.AutomationType;
import com.superagent.core.automation.OverlapPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link AutomationStore} shared by MySQL and ClickHouse. Both backends create their
 * tables themselves; they differ only in {@link #save}, {@link #delete} and whether reads
 * need {@code FINAL}. Runs are append-only.
 */
abstract class AbstractJdbcAutomationStore implements AutomationStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcAutomationStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            TenantSchema.create(jdbc, ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String automationFinal();

    private final RowMapper<Automation> mapper = (rs, n) -> new Automation(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("prompt"),
            AutomationType.valueOf(rs.getString("type")),
            emptyToNull(rs.getString("cron")),
            instant(rs, "run_at"),
            rs.getString("timezone"),
            emptyToNull(rs.getString("model")),
            OverlapPolicy.valueOf(rs.getString("overlap_policy")),
            rs.getBoolean("enabled"),
            rs.getString("source"),
            emptyToNull(rs.getString("owner")),
            emptyToNull(rs.getString("api_token_hash")),
            instant(rs, "next_run_at"),
            instant(rs, "last_run_at"),
            emptyToNull(rs.getString("last_task_id")),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            emptyToNull(rs.getString("tier")));

    private final RowMapper<AutomationRun> runMapper = (rs, n) -> new AutomationRun(
            rs.getString("id"),
            rs.getString("automation_id"),
            rs.getString("trigger_kind"),
            emptyToNull(rs.getString("task_id")),
            emptyToNull(rs.getString("skipped_reason")),
            instant(rs, "fired_at"));

    @Override
    public List<Automation> list() {
        return jdbc.query("SELECT * FROM automation" + automationFinal() + " WHERE company_id = ? ORDER BY created_at DESC", mapper,
                TenantSchema.company());
    }

    @Override
    public Optional<Automation> find(String id) {
        return jdbc.query("SELECT * FROM automation" + automationFinal() + " WHERE company_id = ? AND id = ?", mapper,
                        TenantSchema.company(), id)
                .stream().findFirst();
    }

    @Override
    public void appendRun(AutomationRun r) {
        jdbc.update("INSERT INTO automation_run (company_id, id, automation_id, trigger_kind, task_id, skipped_reason, fired_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), r.id(), r.automationId(), r.trigger(), nn(r.taskId()), nn(r.skippedReason()), utc(r.firedAt()));
    }

    @Override
    public List<AutomationRun> listRuns(String automationId, int limit) {
        return jdbc.query("SELECT * FROM automation_run WHERE company_id = ? AND automation_id = ? ORDER BY fired_at DESC LIMIT ?",
                runMapper, TenantSchema.company(), automationId, limit);
    }

    @Override
    public Optional<AutomationRun> findRunByTask(String taskId) {
        return jdbc.query("SELECT * FROM automation_run WHERE company_id = ? AND task_id = ? ORDER BY fired_at DESC LIMIT 1",
                runMapper, TenantSchema.company(), taskId).stream().findFirst();
    }

    protected static final String COLUMNS =
            "(company_id, id, name, prompt, type, cron, run_at, timezone, model, overlap_policy, enabled, source, owner, "
                    + "api_token_hash, next_run_at, last_run_at, last_task_id, created_at, updated_at, tier)";

    protected static final String VALUES = " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(Automation a) {
        return new Object[]{
                TenantSchema.company(), a.id(), a.name(), a.prompt(), a.type().name(), nn(a.cron()), utc(a.runAt()), a.timezone(),
                nn(a.model()), a.overlap().name(), a.enabled(), a.source(), nn(a.owner()), nn(a.apiTokenHash()),
                utc(a.nextRunAt()), utc(a.lastRunAt()), nn(a.lastTaskId()), utc(a.createdAt()), utc(a.updatedAt()), nn(a.tier())};
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime ldt = rs.getObject(column, LocalDateTime.class);
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    protected static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    protected static String nn(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
