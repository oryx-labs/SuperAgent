package com.superagent.files;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMember;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.file.WorkFile;
import com.superagent.core.security.SecretCipher;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/**
 * Projects' shared folders: one stored file mounted in several projects, only by someone who can see it and is in the
 * project; taking it out keeps the file and everyone's own folders; archived projects take no changes.
 */
class ProjectFolderServiceTest {

    @TempDir
    Path tmp;

    private final FileServiceTest.InMemoryFolders folderStore = new FileServiceTest.InMemoryFolders();

    @BeforeEach
    void inCompany() {
        TenantContext.set("acme");
    }

    @AfterEach
    void leaveCompany() {
        TenantContext.clear();
    }

    private static Agent agent(String id, String owner) {
        return new Agent(id, id, "🙂", null, null, null, AgentStatus.ACTIVE, owner, AgentKind.STANDARD, null,
                Instant.now(), Instant.now());
    }

    private static FileService.Origin chat(String owner, String agent, String message) {
        return new FileService.Origin(owner, agent, null, "s1", message, WorkFile.ORIGIN_CHAT);
    }

    /** ada owns agent "dev" and shares it with bob; carl owns "ops". */
    private FileService service(FileServiceTest.InMemoryFiles store) {
        AgentStore agents = mock(AgentStore.class);
        when(agents.find("dev")).thenReturn(Optional.of(agent("dev", "ada")));
        when(agents.find("ops")).thenReturn(Optional.of(agent("ops", "carl")));
        when(agents.list()).thenReturn(List.of(agent("dev", "ada"), agent("ops", "carl")));
        AgentMemberStore members = mock(AgentMemberStore.class);
        when(members.members("dev")).thenReturn(List.of(new AgentMember("dev", "bob", "ada", true, Instant.now(), Instant.now())));
        when(members.agentsOf("bob")).thenReturn(List.of("dev"));
        TaskStore tasks = mock(TaskStore.class);
        when(tasks.findTask(any())).thenReturn(Optional.empty());
        UserStore users = mock(UserStore.class);
        when(users.findByUsername(any())).thenReturn(Optional.empty());
        Map<String, String> settings = new HashMap<>();
        FileStorageService storage = new FileStorageService(List.of(new LocalFileStoreProvider(tmp.resolve("files").toString())),
                new SystemSettingStore() {
                    @Override public Map<String, String> all() { return settings; }
                    @Override public void put(String key, String value) { settings.put(key, value); }
                }, new SecretCipher("", tmp.resolve("secret.key").toString()), store);
        return new FileService(store, agents, members, tasks, mock(TaskAgentStore.class), users, storage, folderStore,
                tmp.resolve("files").toString(), 1);
    }

    /** Projects' shared folders, in memory. */
    static final class InMemoryProjectFolders implements com.superagent.core.file.ProjectFolderStore {
        final Map<String, com.superagent.core.file.ProjectFolder> rows = new LinkedHashMap<>();
        final Map<String, Map<String, String>> placed = new HashMap<>();
        @Override public void save(com.superagent.core.file.ProjectFolder f) { rows.put(f.id(), f); }
        @Override public Optional<com.superagent.core.file.ProjectFolder> find(String id) {
            return Optional.ofNullable(rows.get(id)).filter(f -> !f.deleted()); }
        @Override public List<com.superagent.core.file.ProjectFolder> list(String projectId) {
            return rows.values().stream().filter(f -> !f.deleted() && f.projectId().equals(projectId)).toList(); }
        @Override public Map<String, String> placements(String projectId) { return new HashMap<>(placed.getOrDefault(projectId, Map.of())); }
        @Override public List<String> projectsOfFile(String fileId) {
            return placed.entrySet().stream().filter(e -> e.getValue().containsKey(fileId)).map(Map.Entry::getKey).toList(); }
        @Override public void place(String projectId, String fileId, String folderId, String by) {
            placed.computeIfAbsent(projectId, k -> new HashMap<>()).put(fileId, folderId); }
        @Override public void unplace(String projectId, String fileId) {
            placed.getOrDefault(projectId, new HashMap<>()).remove(fileId); }
    }

    @Test
    void oneFileMountsInSeveralProjectsAndTakingItOutKeepsIt() throws Exception {
        FileServiceTest.InMemoryFiles store = new FileServiceTest.InMemoryFiles();
        FileService svc = service(store);
        InMemoryProjectFolders shared = new InMemoryProjectFolders();
        // p1: ada, bob, carl; p2: ada only (archived later); p3: carl only.
        Map<String, Set<String>> members = Map.of("p1", Set.of("ada", "bob", "carl"), "p2", Set.of("ada"), "p3", Set.of("carl"));
        Set<String> archived = new java.util.HashSet<>();
        com.superagent.core.project.ProjectAccess projects = mock(com.superagent.core.project.ProjectAccess.class);
        when(projects.member(anyString(), anyString())).thenAnswer(i -> members.get((String) i.getArgument(0)).contains((String) i.getArgument(1)));
        org.mockito.Mockito.doAnswer(i -> {
            if (!members.get((String) i.getArgument(0)).contains((String) i.getArgument(1))) {
                throw new com.superagent.core.error.NotFoundException("no project");
            }
            if (archived.contains((String) i.getArgument(0))) {
                throw new com.superagent.core.error.ConflictException("archived");
            }
            return null;
        }).when(projects).requireWritable(anyString(), anyString());
        when(projects.visibleThroughProject(any(), anyString(), anyString())).thenAnswer(i -> shared.projectsOfFile(i.getArgument(1))
                .stream().anyMatch(p -> members.get(p).contains((String) i.getArgument(2))));
        when(projects.projectsOf(anyString())).thenAnswer(i -> members.keySet().stream().sorted()
                .filter(p -> members.get(p).contains((String) i.getArgument(0)))
                .map(p -> new com.superagent.core.project.Project(p, p, null, "ada", com.superagent.core.project.ProjectStatus.ACTIVE,
                        Instant.now(), Instant.now(), null)).toList());
        when(projects.projectOfTask(any())).thenReturn(Optional.empty());
        when(projects.projectOfAgent("dev")).thenReturn(Optional.of("p1"));
        svc.setProjects(projects);
        ProjectFolderService folders = new ProjectFolderService(shared, svc);
        FileFolderService personal = new FileFolderService(folderStore, svc);
        folders.setProjects(projects);

        WorkFile f = svc.writeText(chat("bob", "dev", "m1"), "方案.md", "内容", null);
        var design = folders.create("ada", "p2", "设计", null);
        assertThatThrownBy(() -> folders.mount("carl", f.id(), "p3", null)).isInstanceOf(WorkFileNotFoundException.class); // can't see it
        folders.mount("ada", f.id(), "p1", null);
        folders.mount("ada", f.id(), "p2", design.id());
        assertThat(store.rows).hasSize(1); // stored once, shown in two projects
        assertThat(folders.trees("ada")).extracting(t -> t.projectId() + ":" + t.placements().get(f.id()))
                .containsExactly("p1:", "p2:" + design.id());
        folders.mount("carl", f.id(), "p3", null); // now carl sees it through p1, and is in p3 too
        folders.unmount("carl", f.id(), "p3");
        assertThatThrownBy(() -> folders.mount("bob", f.id(), "p2", null)).isInstanceOf(com.superagent.core.error.NotFoundException.class);
        assertThatThrownBy(() -> folders.mount("ada", f.id(), "p1", design.id())).hasMessageContaining("没有这个目录");

        // carl's file isn't bob's to see, until carl puts it in a project they share; taking it out keeps the file.
        WorkFile g = svc.writeText(chat("carl", "ops", "m2"), "数据.csv", "a,b", null);
        assertThatThrownBy(() -> svc.get(g.id(), "bob")).isInstanceOf(WorkFileNotFoundException.class);
        var mine = personal.create("carl", "我的数据", null);
        personal.move("carl", g.id(), mine.id());
        folders.mount("carl", g.id(), "p1", null);
        assertThat(svc.get(g.id(), "bob").name()).isEqualTo("数据.csv");
        folders.unmount("carl", g.id(), "p1");
        assertThat(svc.get(g.id(), "carl").folderId()).isEqualTo(mine.id()); // their own folder keeps it
        assertThatThrownBy(() -> svc.get(g.id(), "bob")).isInstanceOf(WorkFileNotFoundException.class);
        assertThat(svc.get(g.id(), "carl").name()).isEqualTo("数据.csv");
        org.mockito.Mockito.verify(projects).forgetFile("p1", g.id());

        // An agent in p1 names a project folder it has; archived projects take no changes.
        var specs = folders.create("bob", "p1", "规格", null);
        assertThat(folders.projectOfRun("dev", null)).contains("p1");
        assertThat(folders.placeByAgent("p1", f.id(), "规格")).isTrue();
        assertThat(folders.trees("bob").get(0).placements()).containsEntry(f.id(), specs.id());
        assertThat(folders.placeByAgent("p1", f.id(), "没有")).isFalse();
        archived.add("p2");
        when(projects.projectArchived("p2")).thenReturn(true);
        assertThat(folders.placeByAgent("p2", f.id(), "设计")).isFalse(); // an archived project takes nothing, agents included
        assertThatThrownBy(() -> folders.unmount("ada", f.id(), "p2")).isInstanceOf(com.superagent.core.error.ConflictException.class);
        assertThatThrownBy(() -> folders.rename("ada", design.id(), "x")).isInstanceOf(com.superagent.core.error.ConflictException.class);
        folders.delete("bob", specs.id()); // its files go up to the root
        assertThat(shared.placements("p1")).containsEntry(f.id(), "");
    }
}
