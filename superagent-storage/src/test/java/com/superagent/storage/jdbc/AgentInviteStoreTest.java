package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentInvite;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class AgentInviteStoreTest {

    @Test
    void savesUpdatesAndListsNewestFirst() {
        MysqlAgentInviteStore store = new MysqlAgentInviteStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:invite-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant t = Instant.parse("2026-11-06T10:00:00Z");
        AgentInvite first = new AgentInvite("i1", "a1", "ada", List.of("bob", "carl"), "s1", "m1",
                AgentInvite.Status.PENDING, t, t);
        store.save(first);
        store.save(new AgentInvite("i2", "a1", "ada", List.of("dan"), "s1", "m2", AgentInvite.Status.PENDING,
                t.plusSeconds(60), t.plusSeconds(60)));
        store.save(first.withStatus(AgentInvite.Status.CONFIRMED));

        assertThat(store.find("i1")).hasValueSatisfying(i -> {
            assertThat(i.usernames()).containsExactly("bob", "carl");
            assertThat(i.status()).isEqualTo(AgentInvite.Status.CONFIRMED);
            assertThat(i.messageId()).isEqualTo("m1");
        });
        assertThat(store.forAgent("a1")).extracting(AgentInvite::id).containsExactly("i2", "i1");
        assertThat(store.forAgent("other")).isEmpty();

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.find("i1")).isEmpty();
            assertThat(store.forAgent("a1")).isEmpty();
        });
    }
}
