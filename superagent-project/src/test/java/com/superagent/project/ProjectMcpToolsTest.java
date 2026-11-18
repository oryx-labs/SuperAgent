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
}
