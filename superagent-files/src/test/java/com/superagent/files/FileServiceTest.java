package com.superagent.files;

import com.superagent.api.file.WorkFileDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMember;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskStore;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileServiceTest {

    private static final class InMemoryFiles implements WorkFileStore {
        final Map<String, WorkFile> rows = new LinkedHashMap<>();
        final Map<String, Set<String>> refs = new LinkedHashMap<>();
        @Override public void save(WorkFile f) { rows.put(f.id(), f); }
        @Override public Optional<WorkFile> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public List<WorkFile> listFor(String owner, Collection<String> agentIds) {
            return rows.values().stream().filter(f -> !f.deleted())
                    .filter(f -> (owner != null && owner.equals(f.owner())) || (f.agentId() != null && agentIds.contains(f.agentId())))
                    .sorted(Comparator.comparing(WorkFile::updatedAt).reversed()).toList();
        }
        @Override public Optional<WorkFile> delivery(String taskId) {
            return rows.values().stream().filter(f -> taskId.equals(f.taskId()) && WorkFile.ORIGIN_DELIVERY.equals(f.origin())).findFirst();
        }
        @Override public void link(String fileId, String sessionId, String messageId) {
            refs.computeIfAbsent(fileId, k -> new LinkedHashSet<>()).add(messageId);
        }
        @Override public Map<String, List<String>> messages(Collection<String> fileIds) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            fileIds.stream().filter(refs::containsKey).forEach(id -> out.put(id, new ArrayList<>(refs.get(id))));
            return out;
        }
    }

    @TempDir
    Path tmp;

    private static Agent agent(String id, String owner) {
        return new Agent(id, id, "🙂", null, null, null, AgentStatus.ACTIVE, owner, AgentKind.STANDARD, null,
                Instant.now(), Instant.now());
    }

    /** ada owns agent "dev" and shares it with bob; carl is an outsider; task t1 was set off by "dev". */
    private FileService service(InMemoryFiles store) {
        AgentStore agents = mock(AgentStore.class);
        when(agents.find("dev")).thenReturn(Optional.of(agent("dev", "ada")));
        when(agents.find("ops")).thenReturn(Optional.of(agent("ops", "carl")));
        when(agents.list()).thenReturn(List.of(agent("dev", "ada"), agent("ops", "carl")));
        AgentMemberStore members = mock(AgentMemberStore.class);
        when(members.members("dev")).thenReturn(List.of(new AgentMember("dev", "bob", "ada", true, Instant.now(), Instant.now())));
        when(members.agentsOf("bob")).thenReturn(List.of("dev"));
        TaskAgentStore taskAgents = mock(TaskAgentStore.class);
        when(taskAgents.agentOf(anyString())).thenAnswer(i -> "t1".equals(i.getArgument(0)) ? Optional.of("dev") : Optional.empty());
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.findTask(any())).thenReturn(Optional.empty());
        UserStore users = mock(UserStore.class);
        when(users.findByUsername(any())).thenReturn(Optional.empty());
        return new FileService(store, agents, members, tasks, taskAgents, users, tmp.resolve("files").toString(), 1);
    }

    private static FileService.Origin chat(String owner, String agent, String message) {
        return new FileService.Origin(owner, agent, null, "s1", message, WorkFile.ORIGIN_CHAT);
    }

    @Test
    void anAgentsFilesAreSharedWithItsMembersAndRevisedInPlace() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        WorkFile draft = svc.writeText(chat("bob", "dev", "m1"), "第01节/讲义.md", "初稿", null);
        assertThat(draft.name()).isEqualTo("第01节_讲义.md"); // a file has no folders

        WorkFile revised = svc.writeText(chat("bob", "dev", "m2"), "第01节讲义.md", "二稿", draft.id());
        assertThat(revised.id()).isEqualTo(draft.id());
        assertThat(Files.readString(svc.content(draft.id(), "ada").path())).isEqualTo("二稿"); // latest only
        assertThatThrownBy(() -> svc.writeText(chat("carl", "ops", "m3"), "x.md", "覆盖", draft.id()))
                .isInstanceOf(IllegalArgumentException.class); // another agent's file

        // bob gave the instruction, ada owns the agent: both see it, both replies show its card; carl doesn't.
        assertThat(svc.list("ada", null)).extracting(WorkFileDto::id).containsExactly(draft.id());
        assertThat(svc.list("bob", "dev").get(0).messageIds()).containsExactly("m1", "m2");
        assertThat(svc.list("carl", null)).isEmpty();
        assertThatThrownBy(() -> svc.get(draft.id(), "carl")).isInstanceOf(WorkFileNotFoundException.class);

        // A plain member who isn't the instruction's giver can't delete; the agent's owner can.
        WorkFile adas = svc.writeText(chat("ada", "dev", "m4"), "提纲.md", "提纲", null);
        assertThat(svc.get(adas.id(), "bob").canManage()).isFalse();
        assertThatThrownBy(() -> svc.delete(adas.id(), "bob")).isInstanceOf(WorkFileForbiddenException.class);
        svc.delete(draft.id(), "ada");
        assertThat(svc.list("bob", null)).extracting(WorkFileDto::id).containsExactly(adas.id());
    }

    @Test
    void aFinishedTasksResultBecomesItsDeliveryFileAndAReRunOverwritesIt() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        svc.onTaskStatus(new NoticeEvents.TaskStatusChanged("t1", "bob", "写周报", "web_chat", "running", null, null));
        assertThat(store.rows).isEmpty();
        svc.onTaskStatus(new NoticeEvents.TaskStatusChanged("t1", "bob", "写周报", "web_chat", "done", null, null, "第一版"));
        svc.onTaskStatus(new NoticeEvents.TaskStatusChanged("t1", "bob", "写周报", "web_chat", "done", null, null, "第二版"));

        assertThat(store.rows).hasSize(1);
        WorkFile delivery = store.rows.values().iterator().next();
        assertThat(delivery.name()).isEqualTo("写周报.md");
        assertThat(delivery.agentId()).isEqualTo("dev"); // so the agent's panel shows it
        assertThat(Files.readString(svc.content(delivery.id(), "ada").path())).isEqualTo("第二版");

        svc.delete(delivery.id(), "bob");
        svc.onTaskStatus(new NoticeEvents.TaskStatusChanged("t1", "bob", "写周报", "web_chat", "done", null, null, "第三版"));
        assertThat(store.rows.values()).allMatch(WorkFile::deleted); // a deleted delivery stays gone
    }

    @Test
    void uploadsLandUnderTheAgentDedupeAndAreDeliveredIntoTheWorkspace() throws Exception {
        InMemoryFiles store = new InMemoryFiles();
        FileService svc = service(store);
        byte[] doc = "需求文档".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // bob (a member of "dev") uploads; the same content again reuses the stored file.
        WorkFileDto first = svc.upload("bob", "dev", null, "需求.docx", new java.io.ByteArrayInputStream(doc));
        WorkFileDto again = svc.upload("bob", "dev", null, "副本.docx", new java.io.ByteArrayInputStream(doc));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.origin()).isEqualTo(WorkFile.ORIGIN_UPLOAD);
        assertThat(svc.list("ada", "dev")).extracting(WorkFileDto::id).contains(first.id()); // the agent's owner sees it
        assertThatThrownBy(() -> svc.upload("carl", "dev", null, "x.txt", new java.io.ByteArrayInputStream(doc)))
                .isInstanceOf(WorkFileNotFoundException.class);

        // Sending links it to the message and copies it into the workspace for the agent to read itself.
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        WorkFileDto image = svc.upload("bob", "dev", null, "图.png", new java.io.ByteArrayInputStream(png));
        FileService.Delivered d = FileService.deliver(svc.attach(List.of(first.id(), image.id()), "bob", "s1", "m1"), ws.toString());
        assertThat(ws.resolve("uploads/需求.docx")).hasBinaryContent(doc);
        assertThat(d.images()).hasSize(1); // the image also goes to the model as an image
        assertThat(d.note()).contains("uploads/需求.docx", "uploads/图.png");
        assertThat(svc.get(first.id(), "bob").messageIds()).containsExactly("m1");
        assertThatThrownBy(() -> svc.attach(List.of(first.id()), "carl", "s1", "m2")).isInstanceOf(WorkFileNotFoundException.class);
    }
}
