package com.superagent.storage.jdbc;

import com.superagent.core.timing.TurnTiming;
import com.superagent.core.timing.TurnTimingStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** JDBC {@link TurnTimingStore} shared by MySQL and ClickHouse; per-tool times are kept as "name\tcount\tms" lines. */
abstract class AbstractJdbcTurnTimingStore implements TurnTimingStore {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcTurnTimingStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    /** "" for MySQL, " FINAL" for ClickHouse. */
    protected abstract String readFinal();

    protected static final String INSERT = "(company_id, message_id, session_id, label, started_at, finished_at, prep_ms, startup_ms, "
            + "model_ms, tools_ms, output_ms, retry_ms, save_ms, attempts, tools) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    protected Object[] params(TurnTiming t) {
        StringBuilder tools = new StringBuilder();
        t.tools().forEach(x -> tools.append(x.name().replace('\t', ' ').replace('\n', ' ')).append('\t')
                .append(x.count()).append('\t').append(x.ms()).append('\n'));
        return new Object[]{TenantSchema.company(), t.messageId(), t.sessionId(), t.label() == null ? "" : t.label(), utc(t.startedAt()),
                utc(t.finishedAt()), t.prepMs(), t.startupMs(), t.modelMs(), t.toolsMs(), t.outputMs(), t.retryMs(),
                t.saveMs(), t.attempts(), tools.toString()};
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private final RowMapper<TurnTiming> mapper = (rs, n) -> {
        List<TurnTiming.ToolTime> tools = new ArrayList<>();
        String raw = rs.getString("tools");
        for (String line : raw == null ? new String[0] : raw.split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 3) {
                tools.add(new TurnTiming.ToolTime(p[0], Integer.parseInt(p[1]), Long.parseLong(p[2])));
            }
        }
        String label = rs.getString("label");
        return new TurnTiming(rs.getString("session_id"), rs.getString("message_id"), label == null || label.isEmpty() ? null : label,
                rs.getObject("started_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("finished_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getLong("prep_ms"), rs.getLong("startup_ms"), rs.getLong("model_ms"), rs.getLong("tools_ms"),
                rs.getLong("output_ms"), rs.getLong("retry_ms"), rs.getLong("save_ms"), rs.getInt("attempts"), tools);
    };

    @Override
    public List<TurnTiming> forSessions(Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        String in = String.join(", ", sessionIds.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>(sessionIds.size() + 1);
        args.add(TenantSchema.company());
        args.addAll(sessionIds);
        return jdbc.query("SELECT * FROM turn_timing" + readFinal() + " WHERE company_id = ? AND session_id IN (" + in + ") ORDER BY started_at",
                mapper, args.toArray());
    }
}
