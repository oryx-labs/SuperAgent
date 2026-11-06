package com.superagent.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class TaskAgentStoreTest {

    @Test
    void listsAnAgentsTasksNewestFirstAndRelinkIsIdempotent() {
        MysqlTaskAgentStore store = new MysqlTaskAgentStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:taskagent-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        store.link("t1", "dev");
        store.link("t2", "dev");
        store.link("t3", "ops");
        store.link("t2", "dev"); // re-link: still one row, moved to the front

        assertThat(store.tasksForAgent("dev")).containsExactly("t2", "t1");
        assertThat(store.tasksForAgent("ops")).containsExactly("t3");
        assertThat(store.tasksForAgent("none")).isEmpty();
        assertThat(store.agentOf("t3")).contains("ops");
        assertThat(store.agentOf("t9")).isEmpty();
    }
}
