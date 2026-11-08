package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskModelChoice;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class TaskModelChoiceStoreTest {

    @Test
    void keepsEachTasksChoicesInOrder() {
        JdbcTaskModelChoiceStore store = store();
        store.save(new TaskModelChoice("t1", 2, TaskModelChoice.BY_ESCALATION, null, "strong", "opus", "claude", null,
                null, "fast model failed", Instant.now()));
        store.save(new TaskModelChoice("t1", 1, TaskModelChoice.BY_JUDGE, "easy", "fast", "haiku", "claude", "opus",
                "- haiku | fast", "a short summary", Instant.now()));
        store.save(new TaskModelChoice("t2", 1, TaskModelChoice.BY_USER, null, "standard", "sonnet", "claude", null,
                null, null, Instant.now()));

        assertThat(store.list("t1")).extracting(TaskModelChoice::model).containsExactly("haiku", "opus");
        assertThat(store.list("t1").get(0).difficulty()).isEqualTo("easy");
        assertThat(store.list("t9")).isEmpty();
    }

    @Test
    void choicesArePerCompany() {
        JdbcTaskModelChoiceStore store = store();
        store.save(new TaskModelChoice("t1", 1, TaskModelChoice.BY_USER, null, "fast", "haiku", "claude", null,
                null, null, Instant.now()));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.list("t1")).isEmpty();
            store.save(new TaskModelChoice("t1", 1, TaskModelChoice.BY_USER, null, "strong", "opus", "claude", null,
                    null, null, Instant.now())); // same (task, seq) key, no clash
        });

        assertThat(store.list("t1")).extracting(TaskModelChoice::model).containsExactly("haiku");
    }

    private static JdbcTaskModelChoiceStore store() {
        return new JdbcTaskModelChoiceStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:choice-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                JdbcTaskModelChoiceStore.MYSQL_DDL);
    }
}
