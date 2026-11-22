package com.superagent.project;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.api.project.ProjectRequests;
import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileKind;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.project.ProjectMemory;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.storage.jdbc.MysqlProjectContextStore;
import com.superagent.storage.jdbc.MysqlProjectFolderStore;
import com.superagent.storage.jdbc.MysqlProjectItemStore;
import com.superagent.storage.jdbc.MysqlProjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who can see and use what through a project, and what archiving stops. */
class ProjectServiceTest {

    private ProjectService service;
    private MysqlProjectFolderStore folders;
    private MysqlProjectItemStore items;
    private final AgentStore agents = mock(AgentStore.class);
    private final WorkFileStore files = mock(WorkFileStore.class);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme"); // the JDBC stores scope every query to a company
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:projsvc-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        UserStore users = mock(UserStore.class);
        when(users.findByUsername(anyString())).thenAnswer(inv -> {
            String u = inv.getArgument(0);
            return List.of("ada", "bob", "cat").contains(u)
                    ? Optional.of(new User(u, u, u.toUpperCase(), Set.of("USER"), true, null, null, null, null, Instant.now(), Instant.now()))
                    : Optional.empty();
        });
        agent("a1", "ada", AgentKind.STANDARD);
        agent("a2", "bob", AgentKind.STANDARD);
        agent("sa", "ada", AgentKind.SUPER);
        folders = new MysqlProjectFolderStore(jdbc);
        items = new MysqlProjectItemStore(jdbc);
        service = new ProjectService(new MysqlProjectStore(jdbc), items, new MysqlProjectContextStore(jdbc), folders,
                files, agents, users);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void agent(String id, String owner, AgentKind kind) {
        Instant now = Instant.now();
        when(agents.find(id)).thenReturn(Optional.of(new Agent(id, id, null, null, null, null, AgentStatus.ACTIVE, owner,
                kind, null, now, now, null)));
    }

    @Test
    void membersShareTheProjectsAgentsAndSeeWhatTheyMade() {
        String pid = service.create("ada", "数据看板", "给 A 客户").id();
        service.addMembers(pid, "ada", List.of("bob"));
        assertThat(service.list("bob")).extracting(p -> p.name()).containsExactly("数据看板");
        assertThatThrownBy(() -> service.get(pid, "cat")).isInstanceOf(NotFoundException.class); // not a member
        assertThatThrownBy(() -> service.addMembers(pid, "bob", List.of("cat"))).isInstanceOf(ForbiddenException.class);

        service.setAgentProject("a1", "ada", pid);
        assertThat(service.sharesProject("a1", "bob")).isTrue();
        assertThat(service.agentsThroughProjects("bob")).containsExactly("a1");
        assertThat(service.sharesProject("a1", "cat")).isFalse();

        service.recordTask("t1", "a1");
        service.recordFile("f1", null, "t1"); // a file follows its task's project
        assertThat(service.visibleThroughProject(ProjectItemKind.FILE, "f1", "bob")).isTrue();
        assertThat(service.get(pid, "bob")).satisfies(p -> {
            assertThat(p.taskCount()).isEqualTo(1);
            assertThat(p.fileCount()).isEqualTo(1);
            assertThat(p.mine()).isFalse();
        });

        service.removeMember(pid, "ada", "bob");
        assertThat(service.sharesProject("a1", "bob")).isFalse();
        assertThat(service.visibleThroughProject(ProjectItemKind.FILE, "f1", "bob")).isFalse();
        assertThatThrownBy(() -> service.removeMember(pid, "ada", "ada")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAnAgentsOwnerMovesItAndArchivingFreezesTheProject() {
        String pid = service.create("ada", "看板", null).id();
        service.addMembers(pid, "ada", List.of("bob"));
        assertThatThrownBy(() -> service.setAgentProject("a1", "bob", pid)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.setAgentProject("sa", "ada", pid)).isInstanceOf(IllegalArgumentException.class);
        service.setAgentProject("a2", "bob", pid); // a member puts their own agent in

        service.archive(pid, "ada");
        assertThat(service.archived("a2")).isTrue();
        assertThatThrownBy(() -> service.setAgentProject("a1", "ada", pid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list("ada").get(0).status()).isEqualTo("archived");

        service.reopen(pid, "ada");
        assertThat(service.archived("a2")).isFalse();
        service.setAgentProject("a2", "bob", null);
        assertThat(service.projectOfAgent("a2")).isEmpty();
    }

    private void file(String id, String name) {
        Instant now = Instant.now();
        when(files.find(id)).thenReturn(Optional.of(new WorkFile(id, name, WorkFileKind.of(name), "text/markdown", 1, "internal", id,
                "ada", "a1", null, "chat", false, now, now, List.of("设计"), "agent")));
    }

    @Test
    void ownerConfiguresReposAndBriefMembersKeepTheMemory() {
        String pid = service.create("ada", "看板", null).id();
        service.addMembers(pid, "ada", List.of("bob"));
        service.saveRepo(pid, "ada", null, new ProjectRequests.Repo("api", "/code/api", "main", "merge", "后端服务"));
        service.saveRepo(pid, "ada", null, new ProjectRequests.Repo("web", "git@github.com:x/web.git", null, "pr", null));
        assertThat(service.repos(pid, "bob")).satisfiesExactly(
                r -> assertThat(r).extracting("name", "location", "defaultBranch", "delivery", "purpose")
                        .containsExactly("api", "/code/api", "main", "merge", "后端服务"),
                r -> assertThat(r).extracting("name", "delivery").containsExactly("web", "pr"));
        assertThat(service.reposOfAgent("a1")).isEmpty(); // not in the project yet
        service.setAgentProject("a1", "ada", pid);
        assertThat(service.reposOfAgent("a1")).hasSize(2);

        assertThatThrownBy(() -> service.saveRepo(pid, "bob", null, new ProjectRequests.Repo("x", "/x", null, null, null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.saveRepo(pid, "ada", null, new ProjectRequests.Repo("x", "/x", null, "push", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveRepo(pid, "ada", null, new ProjectRequests.Repo("x", " ", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveBrief(pid, "bob", new ProjectRequests.Brief("g", null, null, null)))
                .isInstanceOf(ForbiddenException.class);

        String mid = service.addMemory(pid, "bob", new ProjectRequests.Memory("用 UTC", "时间一律存 UTC")).id();
        service.updateMemory(pid, mid, "ada", new ProjectRequests.Memory("时间用 UTC", "库里和接口都用 UTC"));
        assertThat(service.memories(pid, "bob")).singleElement().satisfies(m -> {
            assertThat(m.title()).isEqualTo("时间用 UTC");
            assertThat(m.createdBy()).isEqualTo("bob");
            assertThat(m.updatedBy()).isEqualTo("ada");
        });
        service.deleteMemory(pid, mid, "bob");
        assertThat(service.memories(pid, "ada")).isEmpty();
        assertThatThrownBy(() -> service.memories(pid, "cat")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void agentsInTheProjectGetItsContextAndWriteItsMemory() {
        String pid = service.create("ada", "看板", null).id();
        service.setAgentProject("a1", "ada", pid);
        service.saveBrief(pid, "ada", new ProjectRequests.Brief("十二月上线看板", "给 A 客户", "金额保留两位", "GMV：成交额"));
        service.saveRepo(pid, "ada", null, new ProjectRequests.Repo("api", "/code/api", "main", "merge", "后端服务"));
        file("f1", "需求.md");
        service.recordFile("f1", "a1", null); // a new file of a project agent lands in the project's root
        assertThat(folders.placements(pid)).containsEntry("f1", "");

        ProjectMemory m = service.writeMemoryAsAgent("a1", null, "s1", "ada", null, "接口统一返回 ApiResponse", "不要裸返回");
        assertThat(m).extracting("agentId", "sessionId", "taskId", "createdBy").containsExactly("a1", "s1", null, "ada");
        service.recordTask("t1", "a1");
        assertThat(service.writeMemoryAsAgent(null, "t1", null, "bob", null, "测试用 H2", null).taskId()).isEqualTo("t1");
        assertThatThrownBy(() -> service.writeMemoryAsAgent("a2", null, "s2", "bob", null, "x", null))
                .isInstanceOf(IllegalArgumentException.class); // a2 is in no project

        String ctx = service.context("a1", null).orElseThrow();
        assertThat(ctx).contains("十二月上线看板", "GMV：成交额", "需求.md", "仓库「api」", "后端服务", "接口统一返回 ApiResponse", "不要裸返回");
        assertThat(service.context(null, "t1")).isPresent();
        assertThat(service.context("a2", null)).isEmpty();

        String longText = "很长的说明".repeat(80);
        for (int i = 0; i < 40; i++) {
            service.writeMemoryAsAgent("a1", null, "s1", "ada", null, "约定" + i, longText);
        }
        String capped = service.context("a1", null).orElseThrow();
        assertThat(capped.length()).isLessThanOrEqualTo(ProjectContextBuilder.MAX);
        assertThat(capped).contains("十二月上线看板", "约定", "project_context_read").doesNotContain(longText);
        assertThat(service.readContext("a1", null, null)).contains(longText); // the tool reads it all
    }

    @Test
    void archivedProjectsAreReadOnlyAndEarlierFilesMoveIntoTheirFolders() {
        String pid = service.create("ada", "看板", null).id();
        service.setAgentProject("a1", "ada", pid);
        items.assign(ProjectItemKind.FILE, "old1", pid); // recorded before shared folders existed
        file("old1", "旧方案.md");
        items.assign(ProjectItemKind.FILE, "gone", pid); // deleted since: not mounted
        Instant now = Instant.now();
        when(files.find("gone")).thenReturn(Optional.of(new WorkFile("gone", "删了.md", WorkFileKind.of("删了.md"), "text/markdown",
                1, "internal", "gone", "ada", "a1", null, "chat", true, now, now, List.of(), "agent")));
        assertThat(service.mountLegacyFiles()).isEqualTo(1);
        assertThat(service.mountLegacyFiles()).isZero();
        assertThat(folders.placements(pid)).containsEntry("old1", "").doesNotContainKey("gone");
        service.forgetFile(pid, "old1");
        folders.unplace(pid, "old1");
        assertThat(service.mountLegacyFiles()).isZero(); // taken out stays out

        String mid = service.addMemory(pid, "ada", new ProjectRequests.Memory("坑", null)).id();
        service.archive(pid, "ada");
        assertThatThrownBy(() -> service.saveBrief(pid, "ada", new ProjectRequests.Brief("g", null, null, null)))
                .isInstanceOf(ProjectArchivedException.class);
        assertThatThrownBy(() -> service.saveRepo(pid, "ada", null, new ProjectRequests.Repo("x", "/x", null, null, null)))
                .isInstanceOf(ProjectArchivedException.class);
        assertThatThrownBy(() -> service.updateMemory(pid, mid, "ada", new ProjectRequests.Memory("y", null)))
                .isInstanceOf(ProjectArchivedException.class);
        assertThatThrownBy(() -> service.writeMemoryAsAgent("a1", null, "s1", "ada", null, "x", null))
                .isInstanceOf(ProjectArchivedException.class);
        assertThatThrownBy(() -> service.requireWritable(pid, "ada")).isInstanceOf(ProjectArchivedException.class);
        assertThat(service.projectArchived(pid)).isTrue();
        service.recordFile("new1", "a1", null); // an agent's output doesn't go into an archived project either
        assertThat(folders.placements(pid)).doesNotContainKey("new1");
        assertThat(service.visibleThroughProject(ProjectItemKind.FILE, "new1", "ada")).isFalse();
        assertThat(service.memories(pid, "ada")).hasSize(1); // still readable
        assertThat(service.context("a1", null)).isPresent();

        TenantContext.run("beta", () -> { // another company sees none of it
            assertThat(service.list("ada")).isEmpty();
            assertThat(service.context("a1", null)).isEmpty();
        });
    }
}
