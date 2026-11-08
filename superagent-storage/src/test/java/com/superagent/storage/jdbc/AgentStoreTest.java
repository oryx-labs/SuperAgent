package com.superagent.storage.jdbc;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips the Agent entity and the session→agent link through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class AgentStoreTest {

    private static JdbcTemplate h2() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        return new JdbcTemplate(ds);
    }

    @Test
    void createsFindsUpsertsAndKeepsSoftDeletedRows() {
        MysqlAgentStore store = new MysqlAgentStore(h2()); // constructor creates the table
        Instant now = Instant.now();
        Agent a = new Agent("a1", "Researcher", "🧑‍🔬", "researcher", "You dig deep.", "sonnet", AgentStatus.ACTIVE,
                "alice", AgentKind.STANDARD, null, now, now);

        store.create(a);
        assertThat(store.find("a1")).hasValueSatisfying(got -> {
            assertThat(got.name()).isEqualTo("Researcher");
            assertThat(got.avatar()).isEqualTo("🧑‍🔬");
            assertThat(got.character()).isEqualTo("researcher");
            assertThat(got.persona()).isEqualTo("You dig deep.");
            assertThat(got.model()).isEqualTo("sonnet");
            assertThat(got.status()).isEqualTo(AgentStatus.ACTIVE);
            assertThat(got.owner()).isEqualTo("alice");
            assertThat(got.kind()).isEqualTo(AgentKind.STANDARD);
            assertThat(got.managedBy()).isNull();
        });
        assertThat(store.list()).hasSize(1);

        store.update(a.withName("Analyst")); // upsert, not a new row
        assertThat(store.find("a1")).hasValueSatisfying(got -> assertThat(got.name()).isEqualTo("Analyst"));
        assertThat(store.list()).hasSize(1);

        // Soft delete keeps the row with DELETED status; the service, not the store, filters it out.
        store.update(store.find("a1").orElseThrow().deleted());
        assertThat(store.find("a1")).hasValueSatisfying(got -> assertThat(got.status()).isEqualTo(AgentStatus.DELETED));
        assertThat(store.list()).hasSize(1);
    }

    @Test
    void anotherCompanyNeitherSeesNorOverwritesAnAgent() {
        MysqlAgentStore store = new MysqlAgentStore(h2());
        Instant now = Instant.now();
        store.create(new Agent("a1", "Researcher", null, null, null, null, AgentStatus.ACTIVE, "alice",
                AgentKind.STANDARD, null, now, now));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.find("a1")).isEmpty();
            assertThat(store.list()).isEmpty();
            store.update(new Agent("a1", "Intruder", null, null, null, null, AgentStatus.ACTIVE, "eve",
                    AgentKind.STANDARD, null, now, now));
        });
        assertThat(store.find("a1")).hasValueSatisfying(got -> assertThat(got.name()).isEqualTo("Researcher"));
        assertThat(store.list()).hasSize(1);
    }

    @Test
    void listsSessionsByOwningAgentOnly() {
        JdbcTemplate jdbc = h2();
        MysqlChatStore store = new MysqlChatStore(jdbc);
        Instant now = Instant.now();

        store.createSession(session("s1", "a1", now));
        store.createSession(session("s2", null, now)); // legacy/orphan: no owning agent
        store.createSession(session("s3", "a2", now));

        assertThat(store.listSessionsByAgent("a1")).extracting(ChatSession::id).containsExactly("s1");
        assertThat(store.listSessionsByAgent("missing")).isEmpty();
        // agentId round-trips, including null for orphans.
        assertThat(store.findSession("s1")).hasValueSatisfying(s -> assertThat(s.agentId()).isEqualTo("a1"));
        assertThat(store.findSession("s2")).hasValueSatisfying(s -> assertThat(s.agentId()).isNull());
    }

    private static ChatSession session(String id, String agentId, Instant now) {
        return new ChatSession(id, "t-" + id, AgentType.CLAUDE, null, "/tmp/" + id, agentId,
                SessionStatus.ACTIVE, now, now);
    }
}
