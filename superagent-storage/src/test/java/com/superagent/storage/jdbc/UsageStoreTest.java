package com.superagent.storage.jdbc;

import com.superagent.core.usage.UsageRecord;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class UsageStoreTest {

    @Test
    void keepsUsageByTaskAndByPersonOverTime() {
        JdbcUsageStore store = new JdbcUsageStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:usage-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")), JdbcUsageStore.MYSQL_DDL);
        Instant t = Instant.parse("2026-11-07T00:00:00Z");
        store.save(new UsageRecord("a", t, "ada", "task", "dev", "t1", "s1", "claude", "opus", 100, 2000, 300, 0, 50, 10, 0.12, 0.13));
        store.save(new UsageRecord("b", t.plusSeconds(60), "ada", "chat", "dev", null, "s2", "codex", "gpt", 10, 0, 0, 0, 5, 0, null, null));
        store.save(new UsageRecord("c", t.plusSeconds(120), "bob", "task", null, "t2", null, "claude", "haiku", 1, 0, 0, 0, 1, 0, 0.0, null));

        assertThat(store.forTask("t1")).singleElement().satisfies(r -> {
            assertThat(r.total()).isEqualTo(2450);
            assertThat(r.costUsd()).isEqualTo(0.12);
        });
        assertThat(store.between("ada", t, t.plusSeconds(3600))).extracting(UsageRecord::id).containsExactly("a", "b");
        assertThat(store.between(null, t.plusSeconds(1), t.plusSeconds(3600))).extracting(UsageRecord::id).containsExactly("b", "c");
        assertThat(store.between("ada", t, t.plusSeconds(3600)).get(1).costUsd()).isNull();

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.forTask("t1")).isEmpty();
            assertThat(store.between(null, t, t.plusSeconds(3600))).isEmpty();
        });
    }
}
