package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskModelChoice;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskModelChoiceStoreTest {

    @Test
    void keepsEachTasksChoicesInOrder() {
        JdbcTaskModelChoiceStore store = new JdbcTaskModelChoiceStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:choice-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                JdbcTaskModelChoiceStore.MYSQL_DDL);
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
}
