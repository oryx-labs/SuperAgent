package com.superagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.api.skill.SkillDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.skills.SkillService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Skills go onto an agent only when asked, and only its owner's conversations can change them. */
class AgentSkillMcpToolsTest {

    private final AgentService agents = mock(AgentService.class);
    private final SkillService skills = mock(SkillService.class);
    private final AgentSkillMcpTools tools = new AgentSkillMcpTools(agents, skills);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void attachesToTheAgentBeingTalkedToUnlessAnotherIsNamed() throws Exception {
        when(agents.require("a1", "ada")).thenReturn(agent("a1", "写手", AgentKind.STANDARD));
        when(skills.agentSkillIds("a1")).thenReturn(List.of("s0"));
        when(skills.get("s1", "ada")).thenReturn(skill("s1", "写作规范"));

        McpGrants.Grant chat = new McpGrants.Grant("t", McpGrants.Kind.CHAT, "ada", null, "a1", "sess", "m1", null);
        assertThat(tools.call(chat, "skill_attach", json.readTree("{\"skill_id\":\"s1\"}")).text()).contains("写作规范").contains("写手");
        verify(skills).setAgentSkills("a1", List.of("s0", "s1"), "ada");
    }

    @Test
    void notForOthersAgentsTheSuperAgentItselfOrABackgroundRound() throws Exception {
        when(agents.require("x1", "bob")).thenThrow(new AgentNotFoundException("x1")); // bob only collaborates on it
        McpGrants.Grant bobsChat = new McpGrants.Grant("t", McpGrants.Kind.CHAT, "bob", null, "x1", "sess", "m1", null);
        assertThatThrownBy(() -> tools.call(bobsChat, "skill_attach", json.readTree("{\"skill_id\":\"s1\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("所有者");

        when(agents.require("sa1", "ada")).thenReturn(agent("sa1", "SuperAgent", AgentKind.SUPER));
        McpGrants.Grant superChat = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa1", "sess", "m1", null);
        assertThatThrownBy(() -> tools.call(superChat, "skill_attach", json.readTree("{\"skill_id\":\"s1\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("agent_id"); // must name a member
        assertThatThrownBy(() -> tools.call(superChat, "skill_attach", json.readTree("{\"skill_id\":\"s1\",\"agent_id\":\"sa1\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("SuperAgent");

        McpGrants.Grant background = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa1", null, null, null);
        assertThatThrownBy(() -> tools.call(background, "skill_attach", json.readTree("{\"skill_id\":\"s1\",\"agent_id\":\"a1\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("聊天");
        verify(skills, never()).setAgentSkills(anyString(), anyList(), any());
    }

    private static Agent agent(String id, String name, AgentKind kind) {
        return new Agent(id, name, null, null, null, null, AgentStatus.ACTIVE, "ada", kind, null, Instant.now(), Instant.now());
    }

    private static SkillDto skill(String id, String name) {
        return new SkillDto(id, name, null, null, "", true, "personal", true, List.of(), "ada", "ada", Instant.now(), Instant.now());
    }
}
