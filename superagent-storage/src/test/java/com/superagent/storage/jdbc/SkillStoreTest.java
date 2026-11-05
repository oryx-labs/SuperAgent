package com.superagent.storage.jdbc;

import com.superagent.core.skill.Skill;
import com.superagent.core.skill.SkillStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Round-trips skills and bindings through H2 in MySQL mode. */
class SkillStoreTest {

    @Test
    void upsertsSkillsAndReplacesBindings() {
        MysqlSkillStore store = new MysqlSkillStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:skill-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant now = Instant.now();
        Skill s = new Skill("s1", "写作", "写文章时", "写作", "短句", "team:技术中心", true, false, "ada", "ada", now, now);
        store.save(s);
        store.save(new Skill("s1", "写作规范", "写文章时", "写作", "更短", "team:技术中心", false, false, "ada", "bob", now, now));
        assertThat(store.find("s1")).hasValueSatisfying(got -> {
            assertThat(got.name()).isEqualTo("写作规范");
            assertThat(got.enabled()).isFalse();
            assertThat(got.space()).isEqualTo("team:技术中心");
            assertThat(got.createdBy()).isEqualTo("ada");
            assertThat(got.updatedBy()).isEqualTo("bob");
        });

        assertThat(store.binding(SkillStore.AGENT, "a1")).isEmpty();
        store.bind(SkillStore.AGENT, "a1", List.of("s1", "s2"));
        store.bind(SkillStore.AGENT, "a1", List.of("s2"));
        assertThat(store.binding(SkillStore.AGENT, "a1")).contains(List.of("s2"));
        store.bind(SkillStore.TASK, "t1", List.of());
        assertThat(store.binding(SkillStore.TASK, "t1")).contains(List.of()); // empty, not absent
    }
}
