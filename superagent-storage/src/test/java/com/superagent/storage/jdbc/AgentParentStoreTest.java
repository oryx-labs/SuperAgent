package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent parents are keyed by agent id within a company, through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class AgentParentStoreTest {

    @Test
    void anotherCompanyNeitherSeesNorOverwritesAParent() {
        JdbcAgentParentStore store = new JdbcAgentParentStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:parent-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                JdbcAgentParentStore.MYSQL_DDL);
        store.set("a1", "p1");

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.parentOf("a1")).isEmpty();
            assertThat(store.all()).isEmpty();
            store.set("a1", "p2");
            store.set("a1", null);
        });
        assertThat(store.parentOf("a1")).contains("p1");
        assertThat(store.all()).isEqualTo(Map.of("a1", "p1"));
    }
}
