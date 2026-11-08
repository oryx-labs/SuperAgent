package com.superagent.storage.jdbc;

import com.superagent.core.automation.Automation;
import com.superagent.core.automation.AutomationRun;
import com.superagent.core.automation.AutomationType;
import com.superagent.core.automation.OverlapPolicy;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips automations and their run history through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class AutomationStoreTest {

    private static JdbcTemplate h2() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:automation-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcTemplate(ds);
    }

    @Test
    void savesUpsertsDeletesAndKeepsRunsNewestFirst() {
        MysqlAutomationStore store = new MysqlAutomationStore(h2()); // constructor creates the tables
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Automation a = new Automation("a1", "Daily digest", "Summarize yesterday's news", AutomationType.RECURRING,
                "0 9 * * 1-5", null, "Asia/Shanghai", null, OverlapPolicy.SKIP, true, "chat", "alice", null,
                now.plusSeconds(60), null, null, now, now);

        store.save(a);
        store.save(a.fired(now, "t1").withNextRun(now.plusSeconds(3600)));
        assertThat(store.list()).singleElement().satisfies(got -> {
            assertThat(got.cron()).isEqualTo("0 9 * * 1-5");
            assertThat(got.model()).isNull();
            assertThat(got.lastTaskId()).isEqualTo("t1");
            assertThat(got.nextRunAt()).isEqualTo(now.plusSeconds(3600));
            assertThat(got.source()).isEqualTo("chat");
        });

        store.appendRun(new AutomationRun("r1", "a1", "schedule", "t1", null, now.minusSeconds(10)));
        store.appendRun(new AutomationRun("r2", "a1", "schedule", null, "previous run still going", now));
        assertThat(store.listRuns("a1", 10)).extracting(AutomationRun::id).containsExactly("r2", "r1");
        assertThat(store.listRuns("a1", 10).get(0).taskId()).isNull();

        store.delete("a1");
        assertThat(store.find("a1")).isEmpty();
        assertThat(store.listRuns("a1", 10)).hasSize(2); // history outlives the automation
    }

    @Test
    void automationsAndRunsArePerCompany() {
        MysqlAutomationStore store = new MysqlAutomationStore(h2());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Automation a = new Automation("a1", "Ours", "p", AutomationType.ONCE, null, now, "UTC", null, OverlapPolicy.SKIP,
                true, "chat", "alice", null, now, null, null, now, now);
        store.save(a);
        store.appendRun(new AutomationRun("r1", "a1", "schedule", "t1", null, now));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.list()).isEmpty();
            assertThat(store.listRuns("a1", 10)).isEmpty();
            assertThat(store.findRunByTask("t1")).isEmpty();
            store.delete("a1");
        });

        assertThat(store.find("a1")).isPresent();
        assertThat(store.findRunByTask("t1")).isPresent();
    }
}
