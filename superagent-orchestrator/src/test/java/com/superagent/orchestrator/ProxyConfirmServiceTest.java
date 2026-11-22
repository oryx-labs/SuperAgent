package com.superagent.orchestrator;

import com.superagent.agent.AgentService;
import com.superagent.agent.log.AgentLogService;
import com.superagent.api.proxyconfirm.ProxyConfirmDtos;
import com.superagent.api.task.TaskDto;
import com.superagent.core.error.ConflictException;
import com.superagent.core.notice.NoticeStore;
import com.superagent.core.proxyconfirm.ConfirmType;
import com.superagent.core.proxyconfirm.ProxyConfirmGate;
import com.superagent.core.proxyconfirm.ProxyConfirmRecord;
import com.superagent.core.proxyconfirm.ProxyConfirmRule;
import com.superagent.core.proxyconfirm.ProxyConfirmStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProxyConfirmServiceTest {

    /** Rules and records per company, the way the real store separates them. */
    static class MemoryStore implements ProxyConfirmStore {
        final Map<String, ProxyConfirmRule> rules = new HashMap<>();
        final Map<String, ProxyConfirmRecord> records = new HashMap<>();

        private String k(String... parts) {
            return TenantContext.require() + "|" + String.join("|", parts);
        }

        public Optional<ProxyConfirmRule> findRule(String owner, String projectId) {
            return Optional.ofNullable(rules.get(k(owner, projectId)));
        }

        public void saveRule(ProxyConfirmRule r) {
            rules.put(k(r.owner(), r.projectId()), r);
        }

        public void deleteRule(String owner, String projectId) {
            rules.remove(k(owner, projectId));
        }

        public void saveRecord(ProxyConfirmRecord r) {
            records.put(k(r.id()), r);
        }

        public Optional<ProxyConfirmRecord> findRecord(String id) {
            return Optional.ofNullable(records.get(k(id)));
        }

        public List<ProxyConfirmRecord> records(String owner, int limit) {
            String prefix = TenantContext.require() + "|";
            return records.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue)
                    .filter(r -> owner == null || r.owner().equals(owner)).toList();
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final List<Object> events = new ArrayList<>();
    private final TaskService tasks = mock(TaskService.class);

    @SuppressWarnings("unchecked")
    private ProxyConfirmService service() {
        AgentService agents = mock(AgentService.class);
        when(agents.findSuperAgent(any())).thenReturn(Optional.empty());
        ObjectProvider<TaskService> ts = mock(ObjectProvider.class);
        when(ts.getObject()).thenReturn(tasks);
        ObjectProvider<com.superagent.project.ProjectService> ps = mock(ObjectProvider.class);
        return new ProxyConfirmService(store, mock(NoticeStore.class), agents, mock(AgentLogService.class),
                new ApplicationEventPublisher() {
                    public void publishEvent(Object event) {
                        events.add(event);
                    }
                }, ts, ps);
    }

    private static ProxyConfirmDtos.SaveRule on(String... types) {
        return new ProxyConfirmDtos.SaveRule(true, types.length == 0 ? null : List.of(types));
    }

    private static ProxyConfirmGate.Decision params(ProxyConfirmService s, String project, int open) {
        return s.decide("ada", project, ConfirmType.TASK_PARAMS, "task", "t1", Map.of("openParams", open));
    }

    @Test
    void offByDefaultThenTierOneConfirmsOnlyWhenNothingIsOpen() {
        TenantContext.run("acme-" + UUID.randomUUID(), () -> {
            ProxyConfirmService s = service();
            assertThat(params(s, null, 0).confirmed()).isFalse(); // feature off: as before
            assertThat(store.records("ada", 10)).isEmpty();

            s.save("ada", null, on());
            assertThat(params(s, null, 2).confirmed()).isFalse(); // a ❓ is left
            assertThat(params(s, null, 0).confirmed()).isTrue();
            assertThat(store.records("ada", 10)).singleElement().satisfies(r -> assertThat(r.reason()).isNotBlank());
            assertThat(events).hasSize(1);

            Map<String, Object> spec = Map.of("criteria", 3, "commands", 1, "prdAligned", true);
            assertThat(s.decide("ada", null, ConfirmType.CODING_SPEC, "task", "t2", spec).confirmed()).isTrue();
            assertThat(s.decide("ada", null, ConfirmType.CODING_SPEC, "task", "t3", Map.of("criteria", 3, "commands", 0, "prdAligned", true))
                    .confirmed()).isFalse();
            assertThat(s.decide("ada", null, ConfirmType.WRITING_OUTLINE, "task", "t4", Map.of("sections", 4, "problems", 0)).confirmed()).isTrue();
            assertThat(s.decide("ada", null, ConfirmType.WRITING_OUTLINE, "task", "t5", Map.of("sections", 0, "problems", 0)).confirmed()).isFalse();
        });
    }

    @Test
    void tierTwoNeedsATickAndTierThreeCanNeverBeTurnedOnOrConfirmed() {
        TenantContext.run("acme-" + UUID.randomUUID(), () -> {
            ProxyConfirmService s = service();
            s.save("ada", null, on());
            Map<String, Object> clear = Map.of("clear", true);
            assertThat(s.decide("ada", null, ConfirmType.SUBTASK_SPLIT, "task", "t1", clear).confirmed()).isFalse(); // not ticked

            s.save("ada", null, on("TASK_PARAMS", "SUBTASK_SPLIT"));
            assertThat(s.decide("ada", null, ConfirmType.SUBTASK_SPLIT, "task", "t1", clear).confirmed()).isTrue();
            assertThat(s.decide("ada", null, ConfirmType.CONTINUATION, "task", "t2", clear).confirmed()).isFalse(); // only one kind ticked

            for (ConfirmType t : ConfirmType.values()) {
                if (t.tier() == 3) {
                    assertThatThrownBy(() -> s.save("ada", null, on(t.name()))).isInstanceOf(IllegalArgumentException.class);
                    assertThat(s.decide("ada", null, t, "x", "1", Map.of("clear", true, "openParams", 0)).confirmed()).isFalse();
                }
            }
        });
    }

    @Test
    void projectRuleOverridesGlobal() {
        TenantContext.run("acme-" + UUID.randomUUID(), () -> {
            ProxyConfirmService s = service();
            s.save("ada", null, on());
            s.save("ada", "p-off", new ProxyConfirmDtos.SaveRule(false, null));
            assertThat(params(s, "p-off", 0).confirmed()).isFalse(); // global on, project off
            assertThat(params(s, "p-none", 0).confirmed()).isTrue(); // no project rule: the global one
            s.inherit("ada", "p-off");
            assertThat(params(s, "p-off", 0).confirmed()).isTrue();

            // global off, project on: the project's rule applies
            s.save("bob", null, new ProxyConfirmDtos.SaveRule(false, null));
            s.save("bob", "p-on", on());
            assertThat(s.decide("bob", "p-on", ConfirmType.TASK_PARAMS, "task", "t9", Map.of("openParams", 0)).confirmed()).isTrue();
            assertThat(s.decide("bob", "p-other", ConfirmType.TASK_PARAMS, "task", "t9", Map.of("openParams", 0)).confirmed()).isFalse();

            TenantContext.run("other-" + UUID.randomUUID(), () -> assertThat(params(service(), null, 0).confirmed()).isFalse());
        });
    }

    @Test
    void revokeWorksBeforeTheTaskStartsAndMergeIsNeverRevocable() {
        TenantContext.run("acme-" + UUID.randomUUID(), () -> {
            ProxyConfirmService s = service();
            s.save("ada", null, on("SUBTASK_SPLIT", "MERGE_LOCAL"));
            String id = s.decide("ada", null, ConfirmType.SUBTASK_SPLIT, "task", "t1", Map.of("clear", true)).recordId();
            String merge = s.decide("ada", null, ConfirmType.MERGE_LOCAL, "task", "t2", Map.of("clear", true)).recordId();
            assertThatThrownBy(() -> s.revoke("ada", merge)).isInstanceOf(ConflictException.class).hasMessageContaining("不可撤回");

            TaskDto running = mock(TaskDto.class);
            when(running.status()).thenReturn("running");
            when(tasks.status("t1", "ada")).thenReturn(running);
            assertThatThrownBy(() -> s.revoke("ada", id)).isInstanceOf(ConflictException.class);

            TaskDto pending = mock(TaskDto.class);
            when(pending.status()).thenReturn("pending");
            when(tasks.status("t1", "ada")).thenReturn(pending);
            assertThat(s.revoke("ada", id).status()).isEqualTo(ProxyConfirmRecord.REVOKED);
            verify(tasks).holdForConfirmation("t1", "ada");
            assertThat(s.audit(null, "SUBTASK_SPLIT", null, null)).hasSize(1);
        });
    }

    @Test
    void tierOneShowsTickedForNewUsersAndAConfirmationBindsToItsTypeAndTitle() {
        TenantContext.run("acme-" + UUID.randomUUID(), () -> {
            ProxyConfirmService s = service();
            assertThat(s.rule("ada", null).types()).filteredOn(t -> t.tier() == 1).allMatch(ProxyConfirmDtos.TypeDto::enabled);
            assertThat(s.rule("ada", null).types()).filteredOn(t -> t.tier() != 1).noneMatch(ProxyConfirmDtos.TypeDto::enabled);

            s.save("ada", null, on());
            String id = s.decide("ada", null, ConfirmType.TASK_PARAMS, "task", "写周报", Map.of("openParams", 0)).recordId();
            assertThat(s.claimable("ada", id, "写周报")).contains(ConfirmType.TASK_PARAMS);
            assertThat(s.claimable("ada", id, "别的任务")).isEmpty();
            assertThat(s.claimable("bob", id, "写周报")).isEmpty();
        });
    }

    @Test
    void aSpecIsConfirmedOnlyWhenTheModelCheckAgrees() {
        TenantContext.run("acme-" + UUID.randomUUID(), () -> {
            ProxyConfirmService s = service();
            s.save("ada", null, on());
            java.util.function.Supplier<Boolean> no = () -> false;
            java.util.function.Supplier<Boolean> yes = () -> true;
            assertThat(s.decide("ada", null, ConfirmType.CODING_SPEC, "task", "a", Map.of("criteria", 2, "commands", 1, "prdAligned", no)).confirmed()).isFalse();
            assertThat(s.decide("ada", null, ConfirmType.CODING_SPEC, "task", "b", Map.of("criteria", 2, "commands", 1, "prdAligned", yes)).confirmed()).isTrue();
        });
    }
}
