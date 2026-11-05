package com.superagent.task.automation;

import com.superagent.api.automation.AutomationDto;
import com.superagent.api.automation.AutomationRunDto;
import com.superagent.api.automation.SaveAutomationRequest;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.automation.Automation;
import com.superagent.core.automation.AutomationRun;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.task.TaskStatus;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationServiceTest {

    private static final class InMemoryStore implements AutomationStore {
        final Map<String, Automation> rows = new LinkedHashMap<>();
        final List<AutomationRun> runs = new ArrayList<>();
        @Override public List<Automation> list() { return new ArrayList<>(rows.values()); }
        @Override public Optional<Automation> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Automation save(Automation a) { rows.put(a.id(), a); return a; }
        @Override public void delete(String id) { rows.remove(id); }
        @Override public void appendRun(AutomationRun r) { runs.add(r); }
        @Override public Optional<AutomationRun> findRunByTask(String taskId) {
            return runs.stream().filter(r -> taskId.equals(r.taskId())).findFirst();
        }
        @Override public List<AutomationRun> listRuns(String id, int limit) {
            return runs.stream().filter(r -> r.automationId().equals(id))
                    .sorted(Comparator.comparing(AutomationRun::firedAt).reversed()).limit(limit).toList();
        }
    }

    /** Task-system double: every started task is RUNNING until the test says otherwise. */
    private static final class FakeTasks extends AutomationTasks {
        final List<CreateTaskRequest> started = new ArrayList<>();
        final List<String> owners = new ArrayList<>();
        final Map<String, TaskStatus> statuses = new HashMap<>();
        FakeTasks() { super(null, null); }
        @Override String start(CreateTaskRequest request, String owner) {
            started.add(request);
            owners.add(owner);
            String id = "t" + started.size();
            statuses.put(id, TaskStatus.RUNNING);
            return id;
        }
        @Override Optional<TaskStatus> status(String taskId) { return Optional.ofNullable(statuses.get(taskId)); }
    }

    /** Only "claude-good" passed the inference check. */
    private static final class FakeProviders extends ProviderService {
        FakeProviders() { super(null, null, null, null); }
        @Override public Optional<String> cliFor(String modelId) {
            return "claude-good".equals(modelId) ? Optional.of("claude") : Optional.empty();
        }
    }

    private static final class InMemorySettings implements SystemSettingStore {
        @Override public Map<String, String> all() { return Map.of(); }
        @Override public void put(String key, String value) { }
    }

    private final InMemoryStore store = new InMemoryStore();
    private final FakeTasks tasks = new FakeTasks();
    private final FakeProviders providers = new FakeProviders();
    private final AutomationService service = new AutomationService(store, tasks, providers,
            new SettingsService(new InMemorySettings(), providers));

    private static SaveAutomationRequest recurring(String cron) {
        return new SaveAutomationRequest("Digest", "Summarize yesterday", "recurring", cron, null, null, null, null, null);
    }

    private static SaveAutomationRequest once(LocalDateTime at) {
        return new SaveAutomationRequest(null, "Remind me", "once", null, at.toString(), "Asia/Shanghai", null, null, null);
    }

    @Test
    void createValidatesTheScheduleAndModelAndComputesTheNextRun() {
        AutomationDto a = service.create(recurring("0 9 * * 1-5"), "alice", "chat");
        assertThat(a.nextRunAt()).isAfter(Instant.now());
        assertThat(a.nextRunAt().atZone(ZoneId.of("Asia/Shanghai")).getHour()).isEqualTo(9);
        assertThat(a.source()).isEqualTo("chat");
        assertThat(a.apiToken()).isNull();

        assertThatThrownBy(() -> service.create(recurring("every day at 9"), "alice", "chat"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 fields");
        assertThatThrownBy(() -> service.create(once(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1)),
                "alice", "chat")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("past");
        assertThatThrownBy(() -> service.create(new SaveAutomationRequest(null, "x", "recurring", "0 9 * * *", null,
                null, "listed-but-broken", null, null), "alice", "manual"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inference");
    }

    @Test
    void missedFiringsCatchUpOnceAndOneOffsRetireAfterRunning() {
        String id = service.create(recurring("0 * * * *"), "alice", "manual").id();
        // As if the server was down for the last three hourly firings.
        store.save(store.rows.get(id).withNextRun(Instant.now().minus(Duration.ofHours(3))));

        assertThat(service.fireDue(Instant.now())).isEqualTo(1);
        assertThat(tasks.started).singleElement().satisfies(r -> {
            assertThat(r.source()).isEqualTo("automation");
            assertThat(r.brief()).startsWith("Summarize yesterday").contains("无人值守");
        });
        assertThat(tasks.owners).containsExactly("alice"); // runs as its owner
        assertThat(store.runs).anySatisfy(r -> assertThat(service.forTask(r.taskId(), "alice", false))
                .hasValueSatisfying(a -> assertThat(a.id()).isEqualTo(id)));
        assertThat(service.forTask(store.runs.get(0).taskId(), "bob", false)).isEmpty(); // not bob's
        assertThat(store.rows.get(id).nextRunAt()).isAfter(Instant.now());
        assertThat(service.fireDue(Instant.now())).isZero(); // caught up: nothing more until the next hour

        // Park the hourly one, or "now + 6 min" crossing the hour would fire it here too.
        store.save(store.rows.get(id).withNextRun(Instant.now().plus(Duration.ofDays(1))));
        String oneOff = service.create(once(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(5)),
                "alice", "chat").id();
        assertThat(service.fireDue(Instant.now().plus(Duration.ofMinutes(6)))).isEqualTo(1);
        assertThat(store.rows.get(oneOff).enabled()).isFalse();
        assertThat(store.rows.get(oneOff).nextRunAt()).isNull();
    }

    @Test
    void skipPolicyRecordsASkippedRunWhileThePreviousIsStillRunning() {
        String id = service.create(recurring("0 9 * * *"), "alice", "manual").id();
        assertThat(service.runNow(id, "alice", false).taskId()).isEqualTo("t1");

        AutomationRunDto skipped = service.runNow(id, "alice", false);
        assertThat(skipped.taskId()).isNull();
        assertThat(skipped.status()).isEqualTo("skipped");

        tasks.statuses.put("t1", TaskStatus.NEEDS_CONFIRMATION); // waiting on a human must not block later runs
        assertThat(service.runNow(id, "alice", false).taskId()).isEqualTo("t2");
        assertThat(service.detail(id, "alice", false).runs()).extracting(AutomationRunDto::status)
                .containsExactlyInAnyOrder("running", "skipped", "needs_confirmation");
    }

    @Test
    void apiTriggerNeedsTheTokenAndOnlyTheOwnerOrAnAdminCanChangeIt() {
        AutomationDto a = service.create(new SaveAutomationRequest("Hook", "Handle the event", "api", null, null,
                null, null, null, null), "alice", "manual");
        assertThat(a.apiToken()).hasSize(64);
        assertThat(a.triggerPath()).isEqualTo("/api/v1/automations/" + a.id() + "/trigger");
        assertThat(store.rows.get(a.id()).apiTokenHash()).isNotEqualTo(a.apiToken()); // only the hash is kept

        assertThatThrownBy(() -> service.trigger(a.id(), "wrong", null)).isInstanceOf(AutomationForbiddenException.class);
        service.trigger(a.id(), a.apiToken(), "order #42 shipped");
        assertThat(tasks.started.get(0).brief()).contains("## 本次输入\norder #42 shipped");

        String fresh = service.regenerateToken(a.id(), "alice", false).apiToken();
        assertThatThrownBy(() -> service.trigger(a.id(), a.apiToken(), null))
                .isInstanceOf(AutomationForbiddenException.class); // the old token stops working
        assertThat(fresh).isNotEqualTo(a.apiToken());

        assertThatThrownBy(() -> service.setEnabled(a.id(), false, "bob", false))
                .isInstanceOf(AutomationForbiddenException.class);
        // Reads are scoped too: someone else's automation (and its prompt) doesn't exist for bob.
        assertThat(service.list("bob", false)).isEmpty();
        assertThatThrownBy(() -> service.detail(a.id(), "bob", false)).isInstanceOf(AutomationNotFoundException.class);
        assertThat(service.setEnabled(a.id(), false, "root", true).enabled()).isFalse(); // admin may
        assertThatThrownBy(() -> service.trigger(a.id(), fresh, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUpdateOnlyChangesTheFieldsItNames() {
        String id = service.create(recurring("0 9 * * *"), "alice", "chat").id();
        Instant before = store.rows.get(id).nextRunAt();

        service.update(id, new SaveAutomationRequest(null, null, null, "30 10 * * *", null, null, null, null, null),
                "alice", false);
        Automation after = store.rows.get(id);
        assertThat(after.prompt()).isEqualTo("Summarize yesterday");
        assertThat(after.cron()).isEqualTo("30 10 * * *");
        assertThat(after.nextRunAt()).isNotEqualTo(before);
        assertThat(after.nextRunAt().atZone(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MINUTES).getMinute())
                .isEqualTo(30);
    }
}
