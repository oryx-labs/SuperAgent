package com.superagent.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Managers and team memberships through H2 in MySQL mode, including renaming a team subtree. */
class OrgStoreTest {

    @Test
    void keepsManagersAndTeamsAndRenamesATeamWithEverythingUnderIt() {
        MysqlOrgStore store = new MysqlOrgStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:org-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.setManager("bob", "alice");
        store.setManager("bob", "carol"); // replaced, not added
        assertThat(store.manager("bob")).contains("carol");
        store.setManager("bob", null);
        assertThat(store.manager("bob")).isEmpty();

        store.setTeams("bob", List.of("技术中心/后端组", "市场部"));
        store.setTeams("dan", List.of("技术中心"));
        store.renameTeam("技术中心", "研发中心");
        assertThat(store.teams("bob")).containsExactly("市场部", "研发中心/后端组");
        assertThat(store.teams("dan")).containsExactly("研发中心");
        assertThat(store.memberships()).containsOnlyKeys("bob", "dan");
    }
}
