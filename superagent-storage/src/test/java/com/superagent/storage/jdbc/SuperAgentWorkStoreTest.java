package com.superagent.storage.jdbc;

import com.superagent.core.agent.SuperAgentWork;
import com.superagent.core.agent.SuperAgentWorkEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips a SuperAgent's work record and its events through H2 in MySQL mode. */
class SuperAgentWorkStoreTest {

    @Test
    void keepsOneRowPerTaskScopedToItsOwnerAndEventsInOrder() {
        MysqlSuperAgentWorkStore store = new MysqlSuperAgentWorkStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:sawork-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        SuperAgentWork w = new SuperAgentWork("t1", "alice", "p1", "Tidy the docs", "a1", "Writer",
                SuperAgentWork.RUNNING, null, t, null, t);

        store.save(w);
        store.save(w.withStatus("done", "4 files saved", t.plusSeconds(60))); // upsert, not a new row
        store.save(new SuperAgentWork("t2", "bob", null, "Other", null, null, SuperAgentWork.RUNNING, null, t, null, t));

        assertThat(store.list("alice")).singleElement().satisfies(got -> {
            assertThat(got.status()).isEqualTo("done");
            assertThat(got.open()).isFalse();
            assertThat(got.summary()).isEqualTo("4 files saved");
            assertThat(got.finishedAt()).isEqualTo(t.plusSeconds(60));
        });

        store.appendEvent(new SuperAgentWorkEvent("e2", "t1", "alice", "done", "4 files saved", t.plusSeconds(60)));
        store.appendEvent(new SuperAgentWorkEvent("e1", "t1", "alice", "assigned", "to Writer", t));
        assertThat(store.events("t1")).extracting(SuperAgentWorkEvent::kind).containsExactly("assigned", "done");
    }
}
