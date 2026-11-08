package com.superagent.storage.jdbc;

import com.superagent.core.automation.Automation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** MySQL automation store: self-creating tables, upsert via {@code ON DUPLICATE KEY UPDATE}, hard delete. */
public class MysqlAutomationStore extends AbstractJdbcAutomationStore {

    private static final List<String> SCHEMA = List.of(
            """
            CREATE TABLE IF NOT EXISTS automation (
              company_id     VARCHAR(64)   NOT NULL,
              id             VARCHAR(64)   NOT NULL,
              name           VARCHAR(255)  NOT NULL,
              prompt         MEDIUMTEXT    NOT NULL,
              type           VARCHAR(16)   NOT NULL,
              cron           VARCHAR(128),
              run_at         DATETIME(3),
              timezone       VARCHAR(64)   NOT NULL,
              model          VARCHAR(128),
              overlap_policy VARCHAR(16)   NOT NULL,
              enabled        TINYINT(1)    NOT NULL DEFAULT 1,
              source         VARCHAR(16)   NOT NULL,
              owner          VARCHAR(255),
              api_token_hash VARCHAR(128),
              next_run_at    DATETIME(3),
              last_run_at    DATETIME(3),
              last_task_id   VARCHAR(64),
              created_at     DATETIME(3)   NOT NULL,
              updated_at     DATETIME(3)   NOT NULL,
              PRIMARY KEY (company_id, id),
              KEY idx_automation_next (company_id, next_run_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS automation_run (
              company_id     VARCHAR(64)   NOT NULL,
              id             VARCHAR(64)   NOT NULL,
              automation_id  VARCHAR(64)   NOT NULL,
              trigger_kind   VARCHAR(16)   NOT NULL,
              task_id        VARCHAR(64),
              skipped_reason VARCHAR(512),
              fired_at       DATETIME(3)   NOT NULL,
              PRIMARY KEY (company_id, id),
              KEY idx_automation_run (company_id, automation_id, fired_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

    public MysqlAutomationStore(JdbcTemplate jdbc) {
        super(jdbc, SCHEMA);
        ensureTier();
    }

    private void ensureTier() {
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = SCHEMA() "
                + "AND LOWER(table_name) = 'automation' AND LOWER(column_name) = 'tier'", Integer.class);
        if (present == null || present == 0) {
            jdbc.execute("ALTER TABLE automation ADD COLUMN tier VARCHAR(16)"); // older tables don't get it from CREATE TABLE IF NOT EXISTS
        }
    }

    @Override
    protected String automationFinal() {
        return "";
    }

    @Override
    public Automation save(Automation a) {
        jdbc.update("INSERT INTO automation " + COLUMNS + VALUES
                        + " ON DUPLICATE KEY UPDATE name=VALUES(name), prompt=VALUES(prompt), type=VALUES(type), "
                        + "cron=VALUES(cron), run_at=VALUES(run_at), timezone=VALUES(timezone), model=VALUES(model), "
                        + "overlap_policy=VALUES(overlap_policy), enabled=VALUES(enabled), source=VALUES(source), "
                        + "owner=VALUES(owner), api_token_hash=VALUES(api_token_hash), "
                        + "next_run_at=VALUES(next_run_at), last_run_at=VALUES(last_run_at), "
                        + "last_task_id=VALUES(last_task_id), updated_at=VALUES(updated_at), tier=VALUES(tier)",
                params(a));
        return a;
    }

    @Override
    public void delete(String id) {
        jdbc.update("DELETE FROM automation WHERE company_id = ? AND id = ?", TenantSchema.company(), id);
    }
}
