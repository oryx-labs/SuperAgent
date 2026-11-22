package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMemoryState;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent memory is keyed by agent id within a company, through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class AgentMemoryStateStoreTest {

    @Test
    void anotherCompanyNeitherSeesNorOverwritesAnAgentsMemory() {
        MysqlAgentMemoryStateStore store = new MysqlAgentMemoryStateStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:memory-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant now = Instant.now();
        store.save(new AgentMemoryState("a1", "ada", null, "sonnet", 0, "likes tea", now)
                .writtenByAgent("## 用户偏好\n- likes tea", "用户偏好", "ada", "s1", null));
        assertThat(store.find("a1")).hasValueSatisfying(s -> {
            assertThat(s.agentSections()).isEqualTo("用户偏好");
            assertThat(s.writtenSession()).isEqualTo("s1");
            assertThat(s.writtenAt()).isNotNull();
        });

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.find("a1")).isEmpty();
            store.save(new AgentMemoryState("a1", "eve", null, "sonnet", 0, "likes coffee", now.plusSeconds(1)));
            store.delete("a1");
        });
        assertThat(store.find("a1")).hasValueSatisfying(s -> assertThat(s.memory()).contains("likes tea"));
    }
}
