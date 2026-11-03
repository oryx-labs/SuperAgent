package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskPhaseStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link TaskPhaseStore} shared by MySQL and ClickHouse, mirroring
 * {@link AbstractJdbcTaskStore}: one row per task holding the phase array JSON.
 * Backends differ only in {@link #save} and whether reads need {@code FINAL}.
 */
abstract class AbstractJdbcTaskPhaseStore implements TaskPhaseStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcTaskPhaseStore(JdbcTemplate jdbc, List<String> schemaDdl) {
        this.jdbc = jdbc;
        for (String ddl : schemaDdl) {
            jdbc.execute(ddl);
        }
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String phaseFinal();

    @Override
    public Optional<String> findPhasesJson(String taskId) {
        return jdbc.query("SELECT phases_json FROM task_phase" + phaseFinal() + " WHERE task_id = ?",
                        (rs, n) -> rs.getString("phases_json"), taskId)
                .stream().findFirst();
    }

    protected static LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
