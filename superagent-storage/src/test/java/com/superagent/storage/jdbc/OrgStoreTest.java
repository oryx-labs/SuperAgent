package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Managers and team memberships through H2 in MySQL mode, including renaming a team subtree. */
@ExtendWith(InCompany.class)
class OrgStoreTest {

    @Test
    void keepsManagersAndTeams() {
        MysqlOrgStore store = new MysqlOrgStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:org-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.setManager("bob", "alice");
        store.setManager("bob", "carol"); // replaced, not added
        assertThat(store.manager("bob")).contains("carol");
        store.setManager("bob", null);
        assertThat(store.manager("bob")).isEmpty();

        store.setTeams("bob", List.of("技术中心/后端组", "市场部"));
        store.setTeams("dan", List.of("技术中心"));
        store.setTeams("bob", List.of("市场部"));
        assertThat(store.teams("bob")).containsExactly("市场部");
        assertThat(store.teams("dan")).containsExactly("技术中心");
        assertThat(store.memberships()).containsOnlyKeys("bob", "dan");

        store.setManager("bob", "alice");
        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.managers()).isEmpty();
            assertThat(store.memberships()).isEmpty();
            store.setManager("bob", "eve");
            store.setTeams("bob", List.of());
        });
        assertThat(store.manager("bob")).contains("alice");
        assertThat(store.teams("bob")).containsExactly("市场部");
    }
}
