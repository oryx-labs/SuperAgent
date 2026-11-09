package com.superagent.cost.usage;

import com.superagent.api.usage.UsageDtos;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageStore;
import com.superagent.core.user.UserStore;
import com.superagent.cost.ModelPriceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tokens are kept on whoever the run was for, and priced by list price the way the vendor bills them. */
class TokenUsageServiceTest {

    private final List<UsageRecord> rows = new ArrayList<>();
    private final UsageStore store = new UsageStore() {
        @Override public void save(UsageRecord r) { rows.add(r); }
        @Override public List<UsageRecord> forTask(String taskId) { return rows.stream().filter(r -> taskId.equals(r.taskId())).toList(); }
        @Override public List<UsageRecord> between(String user, Instant from, Instant to) {
            return rows.stream().filter(r -> user == null || user.equals(r.user())).toList();
        }
    };
    private final ModelPriceService prices = mock(ModelPriceService.class);
    private final com.superagent.core.task.TaskStore tasks = mock(com.superagent.core.task.TaskStore.class);
    private final TokenUsageService service = new TokenUsageService(store, prices, mock(AgentStore.class), mock(UserStore.class), tasks);

    @Test
    void pricesLikeTheVendorAndAddsUpPerTaskAndPerPerson() {
        when(prices.price("claude-haiku-4-5")).thenReturn(Optional.of(new ModelPriceService.Price(1, 5, null, null, "anthropic")));
        when(prices.price("unknown-model")).thenReturn(Optional.empty());

        // A real Claude Code run: its own cost was 0.0458055; ours, from the list price, comes to the same.
        Double usd = service.record(new UsageContext("ada", UsageContext.SOURCE_TASK, "dev", "t1", null, "claude"),
                "[{\"model\":\"claude-haiku-4-5\",\"input\":908,\"cacheRead\":0,\"cacheWrite\":35266,\"cacheWrite1h\":0,"
                        + "\"output\":163,\"reasoning\":149,\"cliCostUsd\":0.0458055}]");
        assertThat(usd).isCloseTo(0.0458055, within(1e-9));

        service.record(new UsageContext("ada", UsageContext.SOURCE_MODEL_CHOICE, null, "t1", null, "claude"),
                "[{\"model\":\"unknown-model\",\"input\":10,\"cacheRead\":0,\"cacheWrite\":0,\"cacheWrite1h\":0,\"output\":5,\"reasoning\":0}]");
        service.record(new UsageContext("bob", UsageContext.SOURCE_CHAT, "dev", null, "s1", "codex"),
                "[{\"model\":\"claude-haiku-4-5\",\"input\":1000000,\"cacheRead\":1000000,\"cacheWrite\":0,\"cacheWrite1h\":0,\"output\":0,\"reasoning\":0}]");

        // What a runner actually emits is what gets recorded (the serializer, not a hand-written payload).
        List<String> emitted = new ArrayList<>();
        com.superagent.runtime.cli.Tokens.emit(e -> emitted.add(e.payload()), "s", 1, List.of(
                new com.superagent.core.usage.TokenUsage("claude-haiku-4-5", 1, 2, 3, 1, 4, 1, null),
                new com.superagent.core.usage.TokenUsage("claude-haiku-4-5", 0, 0, 0, 0, 0, 0, null))); // nothing used: dropped
        assertThat(service.record(new UsageContext("ada", UsageContext.SOURCE_TASK, null, "t1", null, "claude"),
                emitted.get(0))).isNotNull();
        rows.remove(rows.size() - 1);

        UsageDtos.Task task = service.forTask("t1");
        assertThat(task.rows()).extracting(UsageDtos.Row::source).containsExactly("task", "model_choice");
        assertThat(task.total().total()).isEqualTo(908 + 35266 + 163 + 15);
        assertThat(task.total().costPartial()).isTrue(); // one model has no price
        assertThat(task.byModel()).extracting(UsageDtos.Total::key).containsExactly("claude-haiku-4-5", "unknown-model");

        // Cache reads at a tenth of the input price when the directory lists none.
        UsageDtos.Summary bob = service.summary("bob", Instant.EPOCH, Instant.now(), ZoneOffset.UTC);
        assertThat(bob.total().costUsd()).isCloseTo(1.1, within(1e-9));
        assertThat(service.summary(null, Instant.EPOCH, Instant.now(), ZoneOffset.UTC).byUser())
                .extracting(UsageDtos.Total::key).containsExactlyInAnyOrder("ada", "bob");

        // Two kinds: ada's task (named, with its models), bob's chat under the agent it was with.
        when(tasks.findTask("t1")).thenReturn(Optional.of(new com.superagent.core.task.Task("t1", "写周报", null,
                com.superagent.core.task.TaskSource.WEB_CHAT, null, null, "general", com.superagent.core.task.TaskStatus.DONE, 100,
                java.math.BigDecimal.ZERO, "claude-haiku-4-5", com.superagent.core.chat.AgentType.CLAUDE, null, "ada", null,
                Instant.now(), null, null, Instant.now(), null)));
        UsageDtos.Summary ada = service.summary("ada", Instant.EPOCH, Instant.now(), ZoneOffset.UTC);
        assertThat(ada.tasks()).singleElement().satisfies(t -> {
            assertThat(t.label()).isEqualTo("写周报");
            assertThat(t.models()).containsExactly("claude-haiku-4-5", "unknown-model");
            assertThat(t.runs()).isEqualTo(2);
        });
        assertThat(ada.agents()).isEmpty();
        assertThat(bob.agents()).singleElement().extracting(UsageDtos.Total::key).isEqualTo("dev");
        assertThat(bob.agentTotal().total()).isEqualTo(bob.total().total());
    }

    @Test
    void aComplexTasksCostIncludesItsSubtasks() {
        List<com.superagent.core.task.TaskLink> links = List.of(
                new com.superagent.core.task.TaskLink("sub1", "main", null, Instant.now()),
                new com.superagent.core.task.TaskLink("sub2", "main", null, Instant.now()));
        service.setLinks(new com.superagent.core.task.TaskLinkStore() {
            @Override public void save(com.superagent.core.task.TaskLink link) { }
            @Override public Optional<com.superagent.core.task.TaskLink> find(String id) { return Optional.empty(); }
            @Override public List<com.superagent.core.task.TaskLink> all() { return links; }
        });
        for (String t : List.of("main", "sub1", "sub2")) {
            service.record(new UsageContext("ada", UsageContext.SOURCE_TASK, null, t, null, "codex"),
                    "[{\"model\":\"m\",\"input\":100,\"cacheRead\":0,\"cacheWrite\":0,\"cacheWrite1h\":0,\"output\":10,\"reasoning\":0}]");
        }

        UsageDtos.Task main = service.forTask("main");
        assertThat(main.total().total()).isEqualTo(330);
        assertThat(main.byTask()).extracting(UsageDtos.Total::key).containsExactlyInAnyOrder("main", "sub1", "sub2");
        // On the usage page the subtasks show under their complex task, not as tasks of their own.
        assertThat(service.summary("ada", Instant.EPOCH, Instant.now(), ZoneOffset.UTC).tasks())
                .singleElement().satisfies(t -> assertThat(t.key()).isEqualTo("main"));
    }
}
