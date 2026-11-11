package com.superagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.error.NotFoundException;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/** The agent_update tool stays in chats, defaults to the talking agent, and turns ownership errors into a notice. */
class AgentUpdateMcpToolsTest {

    private final AgentService agents = mock(AgentService.class);
    private final AgentUpdateMcpTools tools = new AgentUpdateMcpTools(agents);
    private final ObjectMapper json = new ObjectMapper();

    private static final McpGrants.Grant CHAT =
            new McpGrants.Grant("t", McpGrants.Kind.CHAT, "alice", null, "a1", "sess", "m1", null);

    @Test
    void chatOnlyAndDefaultsToTheTalkingAgent() throws Exception {
        when(agents.mergeUpdate(eq("alice"), eq("a1"), isNull(), eq("新人设"), isNull(), isNull(), isNull()))
                .thenReturn("已修改人设。");

        assertThat(tools.call(CHAT, "agent_update", json.readTree("{\"persona\":\"新人设\"}")).text())
                .contains("已修改人设");
        verify(agents).mergeUpdate("alice", "a1", null, "新人设", null, null, null);
    }

    @Test
    void targetsAnotherAgentById() throws Exception {
        when(agents.mergeUpdate(eq("alice"), eq("a2"), eq("新名"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("已修改名称。");
        tools.call(CHAT, "agent_update", json.readTree("{\"agent_id\":\"a2\",\"name\":\"新名\"}"));
        verify(agents).mergeUpdate("alice", "a2", "新名", null, null, null, null);
    }

    @Test
    void skillIdsParsedWhenSent() throws Exception {
        when(agents.mergeUpdate(any(), any(), any(), any(), any(), any(), any())).thenReturn("已修改技能。");
        tools.call(CHAT, "agent_update", json.readTree("{\"skill_ids\":[\"s1\",\"s2\"]}"));
        verify(agents).mergeUpdate("alice", "a1", null, null, null, null, List.of("s1", "s2"));
    }

    @Test
    void noFieldsIsAnError() throws Exception {
        assertThatThrownBy(() -> tools.call(CHAT, "agent_update", json.readTree("{}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("没有要修改的字段");
    }

    @Test
    void nonOwnerBecomesAToolNotice() throws Exception {
        when(agents.mergeUpdate(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new NotFoundException("No such agent: a1"));
        assertThatThrownBy(() -> tools.call(CHAT, "agent_update", json.readTree("{\"name\":\"x\"}")))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void rejectsNonChatGrants() {
        McpGrants.Grant task = new McpGrants.Grant("t", McpGrants.Kind.TASK, "alice", "tk1", null, null, null, null);
        assertThatThrownBy(() -> tools.call(task, "agent_update", json.readTree("{\"name\":\"x\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("对话");
    }

    @Test
    void exposedForTheChatAllowlist() {
        assertThat(AgentUpdateMcpTools.TOOLS).containsExactly("mcp__superagent__agent_update");
        assertThat(tools.kinds()).containsExactly(McpGrants.Kind.CHAT);
        assertThat(tools.tools()).hasSize(1);
        assertThat(tools.tools().get(0).get("description").asText()).contains("确认修改吗？");
    }
}
