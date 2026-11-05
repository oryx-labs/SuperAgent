package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentLogEntry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The run log through H2 in MySQL mode: newest first, filtered by category, and both retention rules. */
class AgentLogStoreTest {

    @Test
    void listsNewestFirstAndPrunesByAgeAndPerAgentCap() {
        MysqlAgentLogStore store = new MysqlAgentLogStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:agentlog-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.append(new AgentLogEntry("old", "a1", "alice", now.minus(Duration.ofDays(40)), "info", "turn", "ancient", null));
        for (int i = 0; i < 5; i++) {
            store.append(new AgentLogEntry("e" + i, "a1", "alice", now.minusSeconds(10 - i), "info",
                    i % 2 == 0 ? "turn" : "session", "line " + i, null));
        }
        store.append(new AgentLogEntry("b0", "b1", "bob", now, "warn", "session", "other agent", null));

        assertThat(store.list("a1", null, 3)).extracting(AgentLogEntry::id).containsExactly("e4", "e3", "e2");
        assertThat(store.list("a1", "session", 10)).extracting(AgentLogEntry::id).containsExactly("e3", "e1");

        store.prune(now.minus(Duration.ofDays(30)), 3); // 30 days, at most 3 lines per agent
        assertThat(store.list("a1", null, 10)).extracting(AgentLogEntry::id).containsExactly("e4", "e3", "e2");
        assertThat(store.list("b1", null, 10)).hasSize(1);
    }
}
