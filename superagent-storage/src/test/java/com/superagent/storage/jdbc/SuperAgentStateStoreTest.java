package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentState;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** One SuperAgent state per person per company, through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class SuperAgentStateStoreTest {

    @Test
    void keepsOneStatePerOwnerWithinEachCompany() {
        MysqlSuperAgentStateStore store = new MysqlSuperAgentStateStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:sastate-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant now = Instant.now();
        SuperAgentState s = new SuperAgentState("ada", "sa1", null, null, null, null, null, now);
        store.save(s);
        store.save(s.withPlan("p1", SuperAgentState.PLAN_PROPOSED, "{}")); // upsert by owner
        assertThat(store.list()).singleElement().satisfies(got -> assertThat(got.planId()).isEqualTo("p1"));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.find("ada")).isEmpty();
            assertThat(store.list()).isEmpty();
            store.save(new SuperAgentState("ada", "sa9", null, null, null, null, null, now));
        });
        assertThat(store.find("ada")).hasValueSatisfying(got -> {
            assertThat(got.agentId()).isEqualTo("sa1");
            assertThat(got.planId()).isEqualTo("p1");
        });
    }
}
