package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips the phase JSON through {@link MysqlTaskPhaseStore} on H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class MysqlTaskPhaseStoreTest {

    private static JdbcTemplate h2() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:phase-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcTemplate(ds);
    }

    @Test
    void savesUpsertsAndDeletes() {
        MysqlTaskPhaseStore store = new MysqlTaskPhaseStore(h2()); // constructor creates the table

        assertThat(store.findPhasesJson("t1")).isEmpty();

        store.save("t1", "[{\"key\":\"prd\"}]");
        assertThat(store.findPhasesJson("t1")).contains("[{\"key\":\"prd\"}]");

        store.save("t1", "[{\"key\":\"prd\"},{\"key\":\"dev\"}]"); // upsert, not duplicate row
        assertThat(store.findPhasesJson("t1")).contains("[{\"key\":\"prd\"},{\"key\":\"dev\"}]");

        store.delete("t1");
        assertThat(store.findPhasesJson("t1")).isEmpty();
    }

    @Test
    void phasesArePerCompany() {
        MysqlTaskPhaseStore store = new MysqlTaskPhaseStore(h2());
        store.save("t1", "[\"ours\"]");

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.findPhasesJson("t1")).isEmpty();
            store.save("t1", "[\"theirs\"]");
            store.delete("t1");
        });

        assertThat(store.findPhasesJson("t1")).contains("[\"ours\"]");
    }
}
