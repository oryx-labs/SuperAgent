package com.superagent.storage.jdbc;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxTodo;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips inbox events, to-dos and source settings through H2 in MySQL mode. */
class InboxStoreTest {

    private static InboxEvent event(String id, EventPriority p, Instant at) {
        return new InboxEvent(id, "ada", "feishu", "ext-" + id, "c1", "研发群", "group", null, "u1", "老板",
                "上线了吗", false, "{}", p, "rule", false, null, at, at, at);
    }

    @Test
    void dedupesPendingAndProcessingRoundTrip() {
        MysqlInboxStore store = new MysqlInboxStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:inbox-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant now = Instant.now();
        assertThat(store.insertIfAbsent(event("e1", EventPriority.P1, now.minusSeconds(60)))).isTrue();
        assertThat(store.insertIfAbsent(event("e1", EventPriority.P1, now))).isFalse(); // redelivery
        store.insertIfAbsent(event("e2", EventPriority.P1, now));
        store.insertIfAbsent(event("e3", EventPriority.P2, now));

        assertThat(store.pending("ada", EventPriority.P1, 10)).extracting(InboxEvent::id).containsExactly("e1", "e2");
        store.update(store.pending("ada", EventPriority.P1, 10).get(0).processedNow().handledAs("todo"));
        assertThat(store.findEvent("e1")).hasValueSatisfying(e -> assertThat(e.handling()).isEqualTo("todo"));
        assertThat(store.pending("ada", EventPriority.P1, 10)).extracting(InboxEvent::id).containsExactly("e2");
        assertThat(store.recent("ada", 10)).hasSize(3);
        assertThat(store.deleteBefore(now.minusSeconds(30))).isEqualTo(1);

        store.save(new InboxTodo("t1", "ada", "回复老板", null, "老板", "今天", "e2", "研发群",
                InboxTodo.TodoStatus.OPEN, null, now, now));
        store.save(store.find("t1").orElseThrow().withStatus(InboxTodo.TodoStatus.TASKED, "task-9"));
        assertThat(store.list("ada")).singleElement().satisfies(t -> assertThat(t.taskId()).isEqualTo("task-9"));

        EventSource s = new EventSource("ada", EventSource.FEISHU, "cli_x", "v1:abc", true, null, "123456",
                "09:00", "Asia/Shanghai", null, null, null, null, null, now, now);
        store.save(s);
        store.save(s.withBinding("ou_ada").withRuns(LocalDate.of(2026, 11, 5), now.minus(Duration.ofHours(1))));
        assertThat(store.find("ada", EventSource.FEISHU)).hasValueSatisfying(got -> {
            assertThat(got.ownerExternalId()).isEqualTo("ou_ada");
            assertThat(got.bindCode()).isNull();
            assertThat(got.lastDigestDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        });
        assertThat(store.listEnabled(EventSource.FEISHU)).hasSize(1);
        assertThat(store.list()).hasSize(1);
    }
}
