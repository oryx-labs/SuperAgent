package com.superagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** agent_memory_write: the chat's agent, or the agent behind a task, writes its own memory for the run's person. */
class AgentMemoryMcpToolsTest {

    private final AgentService agents = mock(AgentService.class);
    private final TaskAgentStore taskAgents = mock(TaskAgentStore.class);
    private final AgentMemoryMcpTools tools = new AgentMemoryMcpTools(agents, taskAgents);
    private final ObjectMapper json = new ObjectMapper();
    private final Agent ada = new Agent("a1", "Ada", null, null, null, null, AgentStatus.ACTIVE, "alice",
            AgentKind.STANDARD, null, Instant.now(), Instant.now());

    @Test
    void chatsAndTasksWriteTheirAgentsMemory() throws Exception {
        assertThat(tools.kinds()).containsExactlyInAnyOrder(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
        when(agents.requireViewer("a1", "alice")).thenReturn(ada);
        when(agents.writeOwnMemory(ada, "alice", "踩过的坑", "- x", false, "s1", null)).thenReturn("踩过的坑");
        McpGrants.Grant chat = new McpGrants.Grant("t", McpGrants.Kind.CHAT, "alice", null, "a1", "s1", "m1", null);
        assertThat(tools.call(chat, "agent_memory_write", json.readTree("{\"category\":\"踩过的坑\",\"content\":\"- x\"}")).text())
                .contains("已记进记忆的「踩过的坑」");

        when(taskAgents.agentOf("t1")).thenReturn(Optional.of("a1"));
        when(agents.writeOwnMemory(ada, "alice", "项目理解", "- y", true, null, "t1")).thenReturn("项目理解");
        McpGrants.Grant task = new McpGrants.Grant("t", McpGrants.Kind.TASK, "alice", "t1", null, null, null, null);
        tools.call(task, "agent_memory_write", json.readTree("{\"category\":\"项目理解\",\"content\":\"- y\",\"mode\":\"replace\"}"));
        verify(agents).writeOwnMemory(ada, "alice", "项目理解", "- y", true, null, "t1");

        assertThatThrownBy(() -> tools.call(chat, "agent_memory_write", json.readTree("{\"category\":\"a\",\"mode\":\"merge\"}")))
                .isInstanceOf(McpToolException.class);
        when(agents.requireViewer("a1", "eve")).thenThrow(new NotFoundException("No such agent: a1"));
        McpGrants.Grant eve = new McpGrants.Grant("t", McpGrants.Kind.CHAT, "eve", null, "a1", "s9", "m1", null);
        assertThatThrownBy(() -> tools.call(eve, "agent_memory_write", json.readTree("{\"category\":\"a\",\"content\":\"b\"}")))
                .isInstanceOf(McpToolException.class);
    }
}
