package com.superagent.handover;

import com.superagent.agent.AgentService;
import com.superagent.api.handover.HandoverPreviewDto;
import com.superagent.api.handover.HandoverResultDto;
import com.superagent.api.user.SaveUserRequest;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.file.FileFolderStore;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.handover.HandoverRecord;
import com.superagent.core.handover.HandoverRecordStore;
import com.superagent.core.project.ProjectStore;
import com.superagent.core.task.TaskStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.files.FileFolderService;
import com.superagent.files.FileService;
import com.superagent.platform.user.PeopleDirectory;
import com.superagent.platform.user.UserService;
import com.superagent.project.ProjectService;
import com.superagent.skills.SkillService;
import com.superagent.task.TaskService;
import com.superagent.task.TaskService.TaskHandoverResult;
import com.superagent.task.automation.AutomationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandoverServiceTest {

    @Mock PeopleDirectory people;
    @Mock UserService users;
    @Mock AgentService agents;
    @Mock TaskService tasks;
    @Mock TaskStore taskStore;
    @Mock AutomationService automations;
    @Mock AutomationStore automationStore;
    @Mock FileService files;
    @Mock FileFolderService folders;
    @Mock FileFolderStore folderStore;
    @Mock WorkFileStore fileStore;
    @Mock ProjectService projects;
    @Mock ProjectStore projectStore;
    @Mock SkillService skills;

    private final MapStore records = new MapStore();
    private HandoverService service;

    private final User alice = user("a1", "alice");
    private final User bob = user("b1", "bob");

    @BeforeEach
    void setUp() {
        service = new HandoverService(people, users, agents, tasks, taskStore, automations, automationStore,
                files, folders, folderStore, fileStore, projects, projectStore, skills, records);
        lenient().when(people.resolve("alice")).thenReturn(Optional.of(alice));
        lenient().when(people.resolve("bob")).thenReturn(Optional.of(bob));
        lenient().when(agents.owned("a1")).thenReturn(List.of());
        lenient().when(taskStore.listTasks()).thenReturn(List.of());
        lenient().when(automationStore.list()).thenReturn(List.of());
        lenient().when(fileStore.listFor(eq("a1"), any())).thenReturn(List.of());
        lenient().when(folderStore.list("a1")).thenReturn(List.of());
        lenient().when(projectStore.list()).thenReturn(List.of());
        lenient().when(skills.countPersonal("a1")).thenReturn(0);
    }

    @Test
    void rejectsSamePerson() {
        assertThatThrownBy(() -> service.preview("alice", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一个人");
    }

    @Test
    void rejectsUnknownUser() {
        when(people.resolve("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview("ghost", "bob"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void emptyAssetsPreviewAndExecuteWithDisable() {
        when(agents.transferOwnership("a1", "b1")).thenReturn(0);
        when(tasks.transferOwnership("a1", "b1")).thenReturn(new TaskHandoverResult(0, 0));
        when(automations.transferOwnership("a1", "b1")).thenReturn(0);
        when(files.transferOwnership("a1", "b1")).thenReturn(0);
        when(folders.transferOwnership("a1", "b1")).thenReturn(0);
        when(projects.transferOwnership("a1", "b1")).thenReturn(0);
        when(skills.transferPersonalSkills("a1", "b1")).thenReturn(0);

        HandoverPreviewDto preview = service.preview("alice", "bob");
        assertThat(preview.agents()).isZero();
        assertThat(preview.warnings()).anyMatch(w -> w.contains("连接器"));

        HandoverResultDto result = service.execute("alice", "bob", true, "admin1");
        assertThat(result.status()).isEqualTo(HandoverRecord.COMPLETED);
        assertThat(result.fromUserDisabled()).isTrue();
        assertThat(records.rows).hasSize(1);
        assertThat(records.rows.get(0).status()).isEqualTo(HandoverRecord.COMPLETED);

        ArgumentCaptor<SaveUserRequest> cap = ArgumentCaptor.forClass(SaveUserRequest.class);
        verify(users).update(eq("a1"), cap.capture());
        assertThat(cap.getValue().enabled()).isFalse();
    }

    @Test
    void executeWithoutDisableLeavesUserEnabled() {
        when(agents.transferOwnership("a1", "b1")).thenReturn(0);
        when(tasks.transferOwnership("a1", "b1")).thenReturn(new TaskHandoverResult(0, 0));
        when(automations.transferOwnership("a1", "b1")).thenReturn(0);
        when(files.transferOwnership("a1", "b1")).thenReturn(0);
        when(folders.transferOwnership("a1", "b1")).thenReturn(0);
        when(projects.transferOwnership("a1", "b1")).thenReturn(0);
        when(skills.transferPersonalSkills("a1", "b1")).thenReturn(0);

        HandoverResultDto result = service.execute("alice", "bob", false, "admin1");
        assertThat(result.fromUserDisabled()).isFalse();
        verify(users, never()).update(anyString(), any());
    }

    @Test
    void previewCountsSuperAgentAndWarns() {
        Agent sa = new Agent("sa1", "SuperAgent", "🦸", null, null, null, AgentStatus.ACTIVE, "a1",
                AgentKind.SUPER, null, Instant.now(), Instant.now());
        when(agents.owned("a1")).thenReturn(List.of(sa));

        HandoverPreviewDto preview = service.preview("alice", "bob");
        assertThat(preview.agents()).isEqualTo(1);
        assertThat(preview.superAgents()).isEqualTo(1);
        assertThat(preview.warnings()).anyMatch(w -> w.contains("SuperAgent"));
    }

    private static User user(String id, String username) {
        Instant now = Instant.now();
        return new User(id, username, username, Set.of("USER"), true, "hash", null, null, UserSource.LOCAL, now, now);
    }

    private static final class MapStore implements HandoverRecordStore {
        final List<HandoverRecord> rows = new ArrayList<>();
        private final ConcurrentHashMap<String, HandoverRecord> byId = new ConcurrentHashMap<>();

        @Override
        public HandoverRecord save(HandoverRecord record) {
            byId.put(record.id(), record);
            rows.removeIf(r -> r.id().equals(record.id()));
            rows.add(record);
            return record;
        }

        @Override
        public Optional<HandoverRecord> find(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<HandoverRecord> list(int limit) {
            return rows.stream().limit(limit).toList();
        }
    }
}
