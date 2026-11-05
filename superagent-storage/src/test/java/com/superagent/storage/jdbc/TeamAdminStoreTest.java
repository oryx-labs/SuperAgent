package com.superagent.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamAdminStoreTest {

    @Test
    void replacesAdminsAndAdminsManageTeamsBelow() {
        MysqlTeamAdminStore store = new MysqlTeamAdminStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:teamadmin-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.set("研发部", List.of("ada", "bob"));
        store.set("研发部", List.of("bob"));
        assertThat(store.all()).containsEntry("研发部", List.of("bob"));
        assertThat(store.manages("bob", "研发部/后端")).isTrue();  // admin of the team above
        assertThat(store.manages("ada", "研发部")).isFalse();      // removed
        assertThat(store.manages("bob", "市场部")).isFalse();
    }
}
