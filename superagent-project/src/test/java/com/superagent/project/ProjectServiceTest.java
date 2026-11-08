package com.superagent.project;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
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
    private final AgentStore agents = mock(AgentStore.class);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme"); // the JDBC stores scope every query to a company
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:projsvc-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
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
        service = new ProjectService(new MysqlProjectStore(jdbc), new MysqlProjectItemStore(jdbc), agents, users);
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
}
