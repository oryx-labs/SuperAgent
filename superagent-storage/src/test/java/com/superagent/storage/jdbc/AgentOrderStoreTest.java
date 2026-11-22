package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class AgentOrderStoreTest {

    @Test
    void ordersAreKeptPerUserAndPerCompany() {
        MysqlAgentOrderStore store = new MysqlAgentOrderStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:order-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));

        store.save("alice", List.of("b", "a", "c"));
        store.save("bob", List.of("a", "b"));
        store.save("alice", List.of("c", "b")); // re-saving moves only those named

        assertThat(store.positions("alice")).containsExactlyInAnyOrderEntriesOf(Map.of("c", 0, "b", 1, "a", 1));
        assertThat(store.positions("bob")).containsExactlyInAnyOrderEntriesOf(Map.of("a", 0, "b", 1));
        TenantContext.run(InCompany.OTHER, () -> assertThat(store.positions("alice")).isEmpty());
    }
}
