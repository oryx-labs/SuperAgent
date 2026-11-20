package com.superagent.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.UserStore;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Agents write the project memory through MCP, with where it came from; outside a project they can't. */
class ProjectMcpToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private ProjectService projects;
    private ProjectMcpTools tools;

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:projmcp-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        AgentStore agents = mock(AgentStore.class);
        Instant now = Instant.now();
        for (String id : new String[]{"a1", "a2"}) {
            when(agents.find(id)).thenReturn(Optional.of(new Agent(id, id, null, null, null, null, AgentStatus.ACTIVE, "ada",
                    AgentKind.STANDARD, null, now, now)));
        }
        UserStore users = mock(UserStore.class);
        when(users.findById(anyString())).thenReturn(Optional.empty());
        projects = new ProjectService(new MysqlProjectStore(jdbc), new MysqlProjectItemStore(jdbc), new MysqlProjectContextStore(jdbc),
                new MysqlProjectFolderStore(jdbc), mock(WorkFileStore.class), agents, users);
        tools = new ProjectMcpTools(projects);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static McpGrants.Grant chat(String agentId) {
        return new McpGrants.Grant("tok", McpGrants.Kind.CHAT, "ada", null, agentId, "s1", "m1", null);
    }

    private ObjectNode args(String... kv) {
        ObjectNode n = json.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    @Test
    void agentsInAProjectWriteItsMemoryWithTheirProvenance() {
        assertThat(tools.kinds()).contains(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
        String pid = projects.create("ada", "看板", null).id();
        projects.setAgentProject("a1", "ada", pid);

        String reply = tools.call(chat("a1"), "project_memory_write", args("title", "接口统一返回 ApiResponse", "content", "不要裸返回")).text();
        assertThat(reply).contains("已记进项目记忆");
        projects.recordTask("t1", "a1");
        McpGrants.Grant task = new McpGrants.Grant("tok", McpGrants.Kind.TASK, "bob", "t1", null, null, null, null);
        tools.call(task, "project_memory_write", args("title", "测试用 H2"));

        assertThat(projects.memories(pid, "ada")).satisfiesExactlyInAnyOrder(
                m -> assertThat(m).extracting("title", "agentId", "sessionId", "taskId", "createdBy")
                        .containsExactly("接口统一返回 ApiResponse", "a1", "s1", null, "ada"),
                m -> assertThat(m).extracting("title", "taskId", "createdBy").containsExactly("测试用 H2", "t1", "bob"));

        String mid = projects.memories(pid, "ada").stream().filter(m -> m.agentId() != null).findFirst().orElseThrow().id();
        tools.call(chat("a1"), "project_memory_write", args("memory_id", mid, "title", "接口统一返回 ApiResponse", "content", "包括错误"));
        assertThat(tools.call(chat("a1"), "project_context_read", args("memory_id", mid)).text()).contains("包括错误");
        assertThat(tools.call(task, "project_context_read", args()).text()).contains("看板", "测试用 H2");

        // Outside any project, and once the project is archived, the tools refuse.
        assertThatThrownBy(() -> tools.call(chat("a2"), "project_memory_write", args("title", "x")))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> tools.call(chat("a2"), "project_context_read", args())).isInstanceOf(McpToolException.class);
        projects.archive(pid, "ada");
        assertThatThrownBy(() -> tools.call(chat("a1"), "project_memory_write", args("title", "x")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("归档");
    }

    @Test
    void anAgentKeepsTheBriefAndReposWhenTheOwnerSetItOff() {
        String pid = projects.create("ada", "看板", null).id();
        projects.setAgentProject("a1", "ada", pid);
        projects.saveBrief(pid, "ada", new com.superagent.api.project.ProjectRequests.Brief("旧目标", "给 A 客户", null, null));

        String reply = tools.call(chat("a1"), "project_update", args("goal", "十二月上线", "glossary", "GMV：成交额")).text();
        assertThat(reply).contains("已更新项目说明");
        assertThat(projects.brief(pid, "ada")).extracting("goal", "background", "glossary", "agentId", "agentName", "updatedBy")
                .containsExactly("十二月上线", "给 A 客户", "GMV：成交额", "a1", "a1", "ada");
        assertThat(projects.context("a1", null)).get().asString().contains("十二月上线", "【沉淀到项目】", "project_update",
                "project_memory_write", "project_folder");

        // Repositories: add, change one field, delete; each change records the agent and the conversation.
        tools.call(chat("a1"), "project_update", args("repo_action", "add", "repo_name", "api", "repo_location", "/code/api",
                "repo_delivery", "pr", "repo_purpose", "后端"));
        var repo = projects.repos(pid, "ada").get(0);
        assertThat(repo).extracting("name", "delivery", "agentId", "updatedBy").containsExactly("api", "pr", "a1", "ada");
        tools.call(chat("a1"), "project_update", args("repo_action", "update", "repo_id", repo.id(), "repo_default_branch", "dev"));
        assertThat(projects.repos(pid, "ada").get(0)).extracting("name", "location", "defaultBranch", "delivery")
                .containsExactly("api", "/code/api", "dev", "pr");
        assertThat(tools.call(chat("a1"), "project_update", args("repo_action", "delete", "repo_id", repo.id())).text())
                .contains("已删除仓库「api」");
        assertThat(projects.repos(pid, "ada")).isEmpty();

        // A run set off by someone who isn't the owner can't; nor can anyone once the project is archived.
        McpGrants.Grant bobs = new McpGrants.Grant("tok", McpGrants.Kind.CHAT, "bob", null, "a1", "s2", "m1", null);
        assertThatThrownBy(() -> tools.call(bobs, "project_update", args("goal", "x")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("需要项目负责人来改");
        assertThatThrownBy(() -> tools.call(chat("a2"), "project_update", args("goal", "x")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("不在任何项目里");
        projects.archive(pid, "ada");
        assertThatThrownBy(() -> tools.call(chat("a1"), "project_update", args("goal", "x")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("归档");
        assertThat(projects.brief(pid, "ada").goal()).isEqualTo("十二月上线");

        // Another company sees none of it.
        TenantContext.set("other");
        assertThatThrownBy(() -> tools.call(chat("a1"), "project_update", args("goal", "x"))).isInstanceOf(McpToolException.class);
        TenantContext.set("acme");
    }

    @Test
    void aTaskSetOffByTheOwnerCanChangeTheBrief() {
        String pid = projects.create("ada", "看板", null).id();
        projects.setAgentProject("a1", "ada", pid);
        projects.recordTask("t1", "a1");
        McpGrants.Grant task = new McpGrants.Grant("tok", McpGrants.Kind.TASK, "ada", "t1", null, null, null, null);
        tools.call(task, "project_update", args("norms", "金额保留两位"));
        assertThat(projects.brief(pid, "ada")).extracting("norms", "updatedBy").containsExactly("金额保留两位", "ada");
    }
}
