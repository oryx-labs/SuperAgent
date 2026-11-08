package com.superagent.storage.jdbc;

import com.superagent.core.chat.AgentType;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskLink;
import com.superagent.core.task.TaskRunState;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Tasks and the per-task tables beside them (run state, links, tags) stay within their company. */
@ExtendWith(InCompany.class)
class TaskStoreTest {

    private static JdbcTemplate h2() {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:task-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static Task task(String id, String title) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Task(id, title, null, TaskSource.TASK_FORM, null, null, null, TaskStatus.PENDING, 0, BigDecimal.ZERO,
                null, AgentType.CLAUDE, null, "ada", null, now, null, null, now);
    }

    @Test
    void tasksAreInvisibleAndUntouchableFromAnotherCompany() {
        MysqlTaskStore store = new MysqlTaskStore(h2());
        store.save(task("t1", "ours"));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.listTasks()).isEmpty();
            assertThat(store.findTask("t1")).isEmpty();
            store.delete("t1");
            store.save(task("t1", "theirs"));
        });

        assertThat(store.listTasks()).singleElement().extracting(Task::title).isEqualTo("ours");
    }

    @Test
    void runStateIsPerCompany() {
        MysqlTaskRunStateStore store = new MysqlTaskRunStateStore(h2());
        store.save(TaskRunState.initial("t1", "s1"));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.find("t1")).isEmpty();
            store.save(TaskRunState.initial("t1", "s2"));
            store.delete("t1");
        });

        assertThat(store.find("t1")).map(TaskRunState::agentSessionId).contains("s1");
    }

    @Test
    void linksArePerCompany() {
        JdbcTaskLinkStore store = new JdbcTaskLinkStore(h2(), JdbcTaskLinkStore.MYSQL_DDL);
        store.save(new TaskLink("t2", "t1", null, Instant.now()));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.find("t2")).isEmpty();
            assertThat(store.all()).isEmpty();
            assertThat(store.children("t1")).isEmpty();
            store.save(new TaskLink("t2", null, "t1", Instant.now()));
            assertThat(store.followUps("t1")).containsExactly("t2");
        });

        assertThat(store.children("t1")).containsExactly("t2");
        assertThat(store.followUps("t1")).isEmpty();
    }

    @Test
    void tagsArePerCompany() {
        JdbcTaskTagStore store = new JdbcTaskTagStore(h2(), JdbcTaskTagStore.MYSQL_DDL);
        store.set("t1", List.of("weekly"));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.tags("t1")).isEmpty();
            assertThat(store.all()).isEmpty();
            store.set("t1", List.of("other"));
        });

        assertThat(store.tags("t1")).containsExactly("weekly");
        assertThat(store.all()).containsOnlyKeys("t1");
    }
}
