package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskOrigin;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class TaskAgentStoreTest {

    @Test
    void listsAnAgentsTasksNewestFirstAndRelinkIsIdempotent() {
        MysqlTaskAgentStore store = store();
        store.link("t1", "dev");
        store.link("t2", "dev");
        store.link("t3", "ops", "s1", "m1"); // set off from a chat reply
        store.link("t2", "dev"); // re-link: still one row, moved to the front

        assertThat(store.tasksForAgent("dev")).containsExactly("t2", "t1");
        assertThat(store.tasksForAgent("ops")).containsExactly("t3");
        assertThat(store.tasksForAgent("none")).isEmpty();
        assertThat(store.agentOf("t3")).contains("ops");
        assertThat(store.agentOf("t9")).isEmpty();
        assertThat(store.origin("t3")).contains(new TaskOrigin("t3", "ops", "s1", "m1"));
        assertThat(store.originsForAgent("dev")).extracting(TaskOrigin::messageId).containsExactly(null, null);
    }

    @Test
    void linksArePerCompany() {
        MysqlTaskAgentStore store = store();
        store.link("t1", "dev");

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.tasksForAgent("dev")).isEmpty();
            assertThat(store.origin("t1")).isEmpty();
            store.link("t1", "ops");
        });

        assertThat(store.agentOf("t1")).contains("dev");
        assertThat(store.originsForAgent("dev")).extracting(TaskOrigin::taskId).containsExactly("t1");
    }

    private static MysqlTaskAgentStore store() {
        return new MysqlTaskAgentStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:taskagent-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
    }
}
