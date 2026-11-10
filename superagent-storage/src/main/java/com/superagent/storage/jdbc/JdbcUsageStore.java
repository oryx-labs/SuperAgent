package com.superagent.storage.jdbc;

import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Token usage on MySQL, or on ClickHouse as analytics data: rows are only appended, so the same SQL serves both. */
public class JdbcUsageStore implements UsageStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id     VARCHAR(64)  NOT NULL,
              id             VARCHAR(64)  NOT NULL,
              at             DATETIME(3)  NOT NULL,
              user_name      VARCHAR(128),
              source         VARCHAR(32)  NOT NULL,
              agent_id       VARCHAR(64),
              task_id        VARCHAR(64),
              session_id     VARCHAR(64),
              cli            VARCHAR(32),
              model          VARCHAR(128),
              input_tokens   BIGINT NOT NULL,
              cache_read     BIGINT NOT NULL,
              cache_write    BIGINT NOT NULL,
              cache_write_1h BIGINT NOT NULL,
              output_tokens  BIGINT NOT NULL,
              reasoning      BIGINT NOT NULL,
              cost_usd       DOUBLE,
              cli_cost_usd   DOUBLE,
              PRIMARY KEY (company_id, id),
              KEY idx_usage_task (company_id, task_id),
              KEY idx_usage_user_at (company_id, user_name, at),
              KEY idx_usage_at (company_id, at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    static final String CLICKHOUSE_DDL = """
            CREATE TABLE IF NOT EXISTS token_usage (
              company_id String, id String, at DateTime64(3, 'UTC'), user_name Nullable(String), source String, agent_id Nullable(String),
              task_id Nullable(String), session_id Nullable(String), cli Nullable(String), model Nullable(String),
              input_tokens Int64, cache_read Int64, cache_write Int64, cache_write_1h Int64, output_tokens Int64,
              reasoning Int64, cost_usd Nullable(Float64), cli_cost_usd Nullable(Float64)
            ) ENGINE = MergeTree ORDER BY (company_id, at, id)
            """;

    private static final String COLUMNS = "id, at, user_name, source, agent_id, task_id, session_id, cli, model, input_tokens, "
            + "cache_read, cache_write, cache_write_1h, output_tokens, reasoning, cost_usd, cli_cost_usd";

    private static final RowMapper<UsageRecord> MAPPER = (rs, i) -> new UsageRecord(rs.getString(1),
            rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10), rs.getLong(11), rs.getLong(12),
            rs.getLong(13), rs.getLong(14), rs.getLong(15), (Double) rs.getObject(16, Double.class),
            (Double) rs.getObject(17, Double.class));

    private final JdbcTemplate jdbc;

    public JdbcUsageStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    @Override
    public void save(UsageRecord r) {
        jdbc.update("INSERT INTO token_usage (company_id, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TenantSchema.company(), r.id(), utc(r.at()), r.user(), r.source(), r.agentId(), r.taskId(), r.sessionId(), r.cli(),
                r.model(), r.input(), r.cacheRead(), r.cacheWrite(), r.cacheWrite1h(), r.output(), r.reasoning(),
                r.costUsd(), r.cliCostUsd());
    }

    @Override
    public List<UsageRecord> forTask(String taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM token_usage WHERE company_id = ? AND task_id = ? ORDER BY at", MAPPER,
                TenantSchema.company(), taskId);
    }

    @Override
    public List<UsageRecord> between(String user, Instant from, Instant to) {
        List<Object> args = new ArrayList<>(List.of(TenantSchema.company(), utc(from), utc(to)));
        String byUser = "";
        if (user != null) {
            byUser = " AND user_name = ?";
            args.add(user);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM token_usage WHERE company_id = ? AND at >= ? AND at < ?" + byUser + " ORDER BY at",
                MAPPER, args.toArray());
    }

    /** Times as UTC wall-clock values: MySQL (UTC connection) and ClickHouse both bind these as proper datetimes. */
    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
