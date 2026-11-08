package com.superagent.storage.jdbc;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC companies, one row each. A global table: the only store not scoped by the current company. MySQL replaces
 * the row; ClickHouse keeps its newest version (ReplacingMergeTree, read with FINAL).
 */
public class JdbcCompanyStore implements CompanyStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS company (
              id              VARCHAR(64)  NOT NULL PRIMARY KEY,
              name            VARCHAR(255) NOT NULL,
              enabled         BOOLEAN      NOT NULL,
              data_key_cipher TEXT,
              max_users         INT,
              max_running_tasks INT,
              monthly_tokens    BIGINT,
              created_at      DATETIME(3)  NOT NULL,
              updated_at      DATETIME(3)  NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    static final String CLICKHOUSE_DDL = """
            CREATE TABLE IF NOT EXISTS company (
              id String, name String, enabled UInt8, data_key_cipher String,
              max_users Nullable(Int32), max_running_tasks Nullable(Int32), monthly_tokens Nullable(Int64),
              created_at DateTime64(3, 'UTC'), updated_at DateTime64(3, 'UTC')
            ) ENGINE = ReplacingMergeTree(updated_at) ORDER BY id
            """;

    private static final String COLUMNS = "(id, name, enabled, data_key_cipher, max_users, max_running_tasks, monthly_tokens, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final RowMapper<Company> MAPPER = (rs, n) -> new Company(rs.getString("id"), rs.getString("name"),
            rs.getBoolean("enabled"), emptyToNull(rs.getString("data_key_cipher")),
            rs.getObject("max_users", Integer.class), rs.getObject("max_running_tasks", Integer.class),
            rs.getObject("monthly_tokens", Long.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;
    private final boolean clickHouse;

    public JdbcCompanyStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        this.clickHouse = ddl.contains("MergeTree");
        jdbc.execute(ddl);
    }

    @Override
    public List<Company> list() {
        return jdbc.query("SELECT * FROM company" + readFinal() + " ORDER BY created_at", MAPPER);
    }

    @Override
    public Optional<Company> find(String id) {
        return jdbc.query("SELECT * FROM company" + readFinal() + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Company save(Company c) {
        Instant now = Instant.now();
        Company row = new Company(c.id(), c.name(), c.enabled(), c.dataKeyCipher(), c.maxUsers(), c.maxRunningTasks(),
                c.monthlyTokens(), c.createdAt() == null ? now : c.createdAt(), now);
        String cipher = row.dataKeyCipher() == null ? (clickHouse ? "" : null) : row.dataKeyCipher();
        jdbc.update((clickHouse ? "INSERT" : "REPLACE") + " INTO company " + COLUMNS, row.id(), row.name(),
                clickHouse ? (row.enabled() ? 1 : 0) : row.enabled(), cipher, row.maxUsers(), row.maxRunningTasks(),
                row.monthlyTokens(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt()));
        return row;
    }

    private String readFinal() {
        return clickHouse ? " FINAL" : "";
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
