package com.superagent.storage.jdbc;

import com.superagent.core.timing.TurnTiming;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(InCompany.class)
class TurnTimingStoreTest {

    @Test
    void keepsEveryStageAndTheToolsPerSession() {
        MysqlTurnTimingStore store = store();
        Instant t = Instant.parse("2026-11-07T10:00:00Z");
        store.save(new TurnTiming("s1", "m1", "prd", t, t.plusMillis(9_000), 100, 3_000, 2_000, 2_500, 1_000, 300, 100, 2,
                List.of(new TurnTiming.ToolTime("Bash", 2, 2_000), new TurnTiming.ToolTime("mcp__superagent__file_write", 1, 500))));
        store.save(new TurnTiming("s2", "m2", null, t, t.plusMillis(1_000), 1_000, 0, 0, 0, 0, 0, 0, 0, List.of()));

        assertThat(store.forSessions(List.of("s1"))).singleElement().satisfies(x -> {
            assertThat(x.label()).isEqualTo("prd");
            assertThat(x.totalMs()).isEqualTo(9_000);
            assertThat(x.attempts()).isEqualTo(2);
            assertThat(x.tools()).extracting(TurnTiming.ToolTime::name).containsExactly("Bash", "mcp__superagent__file_write");
        });
        assertThat(store.forSessions(List.of("s2")).get(0).label()).isNull();
        assertThat(store.forSessions(List.of())).isEmpty();
    }

    @Test
    void timingsArePerCompany() {
        MysqlTurnTimingStore store = store();
        Instant t = Instant.parse("2026-11-07T10:00:00Z");
        store.save(new TurnTiming("s1", "m1", "ours", t, t.plusMillis(1_000), 0, 0, 0, 0, 0, 0, 0, 1, List.of()));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.forSessions(List.of("s1"))).isEmpty();
            store.save(new TurnTiming("s1", "m1", "theirs", t, t.plusMillis(1_000), 0, 0, 0, 0, 0, 0, 0, 1, List.of()));
        });

        assertThat(store.forSessions(List.of("s1"))).singleElement().extracting(TurnTiming::label).isEqualTo("ours");
    }

    private static MysqlTurnTimingStore store() {
        return new MysqlTurnTimingStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:timing-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
    }
}
