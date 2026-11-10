package com.superagent.storage.jdbc;

import com.superagent.core.tenant.Company;
import com.superagent.core.tenant.CompanyStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC companies, one row each. A global table: the only store not scoped by the current company. Kept in MySQL
 * (business data always is); a save replaces the row.
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


    private static final String COLUMNS = "(id, name, enabled, data_key_cipher, max_users, max_running_tasks, monthly_tokens, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final RowMapper<Company> MAPPER = (rs, n) -> new Company(rs.getString("id"), rs.getString("name"),
            rs.getBoolean("enabled"), emptyToNull(rs.getString("data_key_cipher")),
            rs.getObject("max_users", Integer.class), rs.getObject("max_running_tasks", Integer.class),
            rs.getObject("monthly_tokens", Long.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcCompanyStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        jdbc.execute(ddl);
        addLimitColumns();
    }

    /**
     * Tables made before companies had limits lack these columns. Unlike company-scoped tables they are kept, not
     * rebuilt: a company's data key is in its row, and losing it would make that company's secrets unreadable.
     */
    private void addLimitColumns() {
        try {
            jdbc.queryForList("SELECT max_users, max_running_tasks, monthly_tokens FROM company WHERE 1 = 0");
        } catch (DataAccessException missing) {
            jdbc.execute("ALTER TABLE company ADD COLUMN max_users INT");
            jdbc.execute("ALTER TABLE company ADD COLUMN max_running_tasks INT");
            jdbc.execute("ALTER TABLE company ADD COLUMN monthly_tokens BIGINT");
        }
    }

    @Override
    public List<Company> list() {
        return jdbc.query("SELECT * FROM company ORDER BY created_at", MAPPER);
    }

    @Override
    public Optional<Company> find(String id) {
        return jdbc.query("SELECT * FROM company WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Company save(Company c) {
        Instant now = Instant.now();
        Company row = new Company(c.id(), c.name(), c.enabled(), c.dataKeyCipher(), c.maxUsers(), c.maxRunningTasks(),
                c.monthlyTokens(), c.createdAt() == null ? now : c.createdAt(), now);
        jdbc.update("REPLACE INTO company " + COLUMNS, row.id(), row.name(),
                row.enabled(), row.dataKeyCipher(), row.maxUsers(), row.maxRunningTasks(),
                row.monthlyTokens(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt()));
        return row;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}
