package com.superagent.storage.jdbc;

import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileKind;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkFileStoreTest {

    private static WorkFile file(String id, String owner, String agent, String task, String origin, Instant at) {
        return new WorkFile(id, id + ".md", WorkFileKind.DOCUMENT, "text/markdown", 3, WorkFile.INTERNAL, id,
                owner, agent, task, origin, false, at, at);
    }

    @Test
    void listsByOwnerOrAgentAndKeepsTheRepliesThatProducedAFile() {
        MysqlWorkFileStore store = new MysqlWorkFileStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:workfile-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant t = Instant.parse("2026-11-06T00:00:00Z");
        store.save(file("a", "ada", null, null, WorkFile.ORIGIN_CHAT, t));
        store.save(file("b", "bob", "shared", null, WorkFile.ORIGIN_CHAT, t.plusSeconds(1)));
        store.save(file("c", "bob", null, "t1", WorkFile.ORIGIN_DELIVERY, t.plusSeconds(2)));
        store.save(store.find("a").orElseThrow().markDeleted());

        // ada sees her own (deleted one gone) and what the shared agent made for bob; not bob's private task.
        assertThat(store.listFor("ada", List.of("shared"))).extracting(WorkFile::id).containsExactly("b");
        assertThat(store.listFor("bob", List.of())).extracting(WorkFile::id).containsExactly("c", "b");
        assertThat(store.delivery("t1")).map(WorkFile::id).contains("c");
        assertThat(store.find("b").orElseThrow().agentId()).isEqualTo("shared");
        assertThat(store.find("c").orElseThrow().agentId()).isNull();

        store.link("b", "s1", "m1");
        store.link("b", "s1", "m2");
        store.link("b", "s1", "m1"); // idempotent
        assertThat(store.messages(List.of("b", "c"))).containsOnlyKeys("b");
        assertThat(store.messages(List.of("b")).get("b")).containsExactlyInAnyOrder("m1", "m2"); // once each
    }
}
