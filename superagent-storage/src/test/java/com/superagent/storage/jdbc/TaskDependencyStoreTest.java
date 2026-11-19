package com.superagent.storage.jdbc;

import com.superagent.core.task.TaskDependency;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(InCompany.class)
class TaskDependencyStoreTest {

    private static JdbcTaskDependencyStore store() {
        return new JdbcTaskDependencyStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:dep-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                JdbcTaskDependencyStore.MYSQL_DDL);
    }

    @Test
    void dependenciesAreRecordedNotifiedAndClearedWithinTheirCompany() {
        JdbcTaskDependencyStore store = store();
        store.save(new TaskDependency("b", "a", Instant.now(), null));
        store.save(new TaskDependency("b", "c", Instant.now(), null));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.all()).isEmpty();
            assertThat(store.of("b")).isEmpty();
            store.clear("b");
            store.markNotified("b", "a", Instant.now());
        });

        assertThat(store.of("b")).extracting(TaskDependency::dependsOn).containsExactlyInAnyOrder("a", "c");
        assertThat(store.of("b")).allMatch(d -> d.notifiedAt() == null);
        store.markNotified("b", "a", Instant.now());
        assertThat(store.of("b")).filteredOn(d -> d.dependsOn().equals("a")).singleElement()
                .satisfies(d -> assertThat(d.notifiedAt()).isNotNull());
        store.clear("b");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void withoutACompanyNothingIsRead() {
        JdbcTaskDependencyStore store = store();
        TenantContext.clear();
        assertThatThrownBy(store::all).isInstanceOf(IllegalStateException.class);
    }
}
