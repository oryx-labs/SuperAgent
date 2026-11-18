package com.superagent.storage.jdbc;

import com.superagent.core.task.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL task store: upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlTaskStore extends AbstractJdbcTaskStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS task (
              company_id    VARCHAR(64)   NOT NULL,
              id            VARCHAR(64)   NOT NULL,
              title         VARCHAR(512)  NOT NULL,
              brief         MEDIUMTEXT,
              source        VARCHAR(16)   NOT NULL,
              repo          VARCHAR(512),
              branch        VARCHAR(255),
              flow          VARCHAR(255),
              status        VARCHAR(32)   NOT NULL,
              progress      INT           NOT NULL DEFAULT 0,
              spend         DECIMAL(16,6) NOT NULL DEFAULT 0,
              model         VARCHAR(128),
              agent_type    VARCHAR(32)   NOT NULL,
              workspace_dir VARCHAR(1024),
              created_by    VARCHAR(255),
              error         MEDIUMTEXT,
              created_at    DATETIME(3)   NOT NULL,
              started_at    DATETIME(3),
              finished_at   DATETIME(3),
              updated_at    DATETIME(3)   NOT NULL,
              seq           BIGINT,
              PRIMARY KEY (company_id, id),
              KEY idx_task_created (company_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            // The last number each company handed out: a counter, so a deleted task's number isn't reused.
            """
            CREATE TABLE IF NOT EXISTS task_seq (
              company_id VARCHAR(64) NOT NULL,
              last_seq   BIGINT      NOT NULL,
              PRIMARY KEY (company_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlTaskStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        ensureTier();
        ensureSpendPrecision();
        ensureNumbers();
    }

    /** Older tables get the column, and tasks made before numbering get numbers in the order they were created. */
    private void ensureNumbers() {
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'task' AND LOWER(column_name) = 'seq'", Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE task ADD COLUMN seq BIGINT");
        }
        // A one-off migration across every company, before any request runs: no tenant to scope it to.
        List<java.util.Map<String, Object>> unnumbered = jdbc.queryForList(
                "SELECT company_id, id FROM task WHERE seq IS NULL ORDER BY company_id, created_at, id");
        String company = null;
        long next = 0;
        for (java.util.Map<String, Object> row : unnumbered) {
            String c = (String) row.get("company_id");
            if (!c.equals(company)) {
                company = c;
                next = highest(c);
            }
            jdbc.update("UPDATE task SET seq = ? WHERE company_id = ? AND id = ?", ++next, c, row.get("id"));
            raiseCounter(c, next);
        }
    }

    private long highest(String company) {
        Long inTasks = jdbc.queryForObject("SELECT MAX(seq) FROM task WHERE company_id = ?", Long.class, company);
        List<Long> counter = jdbc.queryForList("SELECT last_seq FROM task_seq WHERE company_id = ?", Long.class, company);
        return Math.max(inTasks == null ? 0 : inTasks, counter.isEmpty() ? 0 : counter.get(0));
    }

    private void raiseCounter(String company, long to) {
        if (jdbc.update("UPDATE task_seq SET last_seq = ? WHERE company_id = ? AND last_seq < ?", to, company, to) == 0
                && jdbc.queryForList("SELECT 1 FROM task_seq WHERE company_id = ?", company).isEmpty()) {
            jdbc.update("INSERT INTO task_seq (company_id, last_seq) VALUES (?, ?)", company, to);
        }
    }

    @Override
    public void assignNumber(String id) {
        String company = TenantSchema.company();
        // One transaction: the counter row stays locked from the bump to the read, so two tasks never share a number.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                long n;
                try (var bump = con.prepareStatement("UPDATE task_seq SET last_seq = last_seq + 1 WHERE company_id = ?")) {
                    bump.setString(1, company);
                    if (bump.executeUpdate() == 0) {
                        try (var first = con.prepareStatement(
                                "INSERT INTO task_seq (company_id, last_seq) SELECT ?, COALESCE(MAX(seq), 0) + 1 FROM task WHERE company_id = ?")) {
                            first.setString(1, company);
                            first.setString(2, company);
                            first.executeUpdate();
                        }
                    }
                }
                try (var read = con.prepareStatement("SELECT last_seq FROM task_seq WHERE company_id = ?")) {
                    read.setString(1, company);
                    try (var rs = read.executeQuery()) {
                        rs.next();
                        n = rs.getLong(1);
                    }
                }
                try (var set = con.prepareStatement("UPDATE task SET seq = ? WHERE company_id = ? AND id = ? AND seq IS NULL")) {
                    set.setLong(1, n);
                    set.setString(2, company);
                    set.setString(3, id);
                    set.executeUpdate();
                }
                con.commit();
                return n;
            } catch (java.sql.SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(auto);
            }
        });
    }

    @Override
    public java.util.Map<String, Long> numbers() {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        jdbc.query("SELECT id, seq FROM task WHERE company_id = ? AND seq IS NOT NULL",
                rs -> { out.put(rs.getString("id"), rs.getLong("seq")); }, TenantSchema.company());
        return out;
    }

    /** Cents were too coarse: one small task costs a fraction of a cent, which showed as $0.00. */
    private void ensureSpendPrecision() {
        Integer scale = jdbc.queryForObject("SELECT MAX(numeric_scale) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'task' AND LOWER(column_name) = 'spend'", Integer.class);
        if (scale != null && scale < 6) {
            jdbc.execute("ALTER TABLE task MODIFY spend DECIMAL(16,6) NOT NULL DEFAULT 0");
        }
    }

    private void ensureTier() {
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'task' AND LOWER(column_name) = 'tier'", Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE task ADD COLUMN tier VARCHAR(16)"); // older tables don't get it from CREATE TABLE IF NOT EXISTS
        }
    }

    @Override
    protected String taskFinal() {
        return "";
    }

    @Override
    public Task save(Task t) {
        jdbc.update("INSERT INTO task " + COLUMNS + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE title=VALUES(title), brief=VALUES(brief), source=VALUES(source), "
                        + "repo=VALUES(repo), branch=VALUES(branch), flow=VALUES(flow), status=VALUES(status), "
                        + "progress=VALUES(progress), spend=VALUES(spend), model=VALUES(model), "
                        + "agent_type=VALUES(agent_type), workspace_dir=VALUES(workspace_dir), "
                        + "created_by=VALUES(created_by), error=VALUES(error), started_at=VALUES(started_at), "
                        + "finished_at=VALUES(finished_at), updated_at=VALUES(updated_at), tier=VALUES(tier)",
                params(t));
        return t;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM task WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
