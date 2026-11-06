package com.superagent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.agent.AgentInviteService;
import com.superagent.agent.AgentNotFoundException;
import com.superagent.agent.AgentService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentInvite;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The SuperAgent invites people to its user's agents from its chat — only there, and only to agents the user owns. */
class SuperAgentCollabToolsTest {

    private final AgentService agents = mock(AgentService.class);
    private final AgentInviteService invites = mock(AgentInviteService.class);
    private final SuperAgentService superAgent = mock(SuperAgentService.class);
    private final SuperAgentCollabTools tools = new SuperAgentCollabTools(agents, invites, superAgent);
    private final ObjectMapper json = new ObjectMapper();

    private final McpGrants.Grant chat = grant("chat-token", "sa-session", "reply-1");

    @Test
    void invitesFromTheChatBecomeACardUnderItsReply() throws Exception {
        when(agents.require("m1", "ada")).thenReturn(agent("m1", "市场调研员", AgentKind.STANDARD, "sa1"));
        when(invites.propose(eq("m1"), eq("ada"), anyList(), any(), any())).thenReturn(new AgentInvite("i1", "m1", "ada",
                List.of("bob"), "sa-session", "reply-1", AgentInvite.Status.PENDING, Instant.now(), Instant.now()));

        String reply = tools.call(chat, "collab_invite", json.readTree("{\"agent_id\":\"m1\",\"usernames\":[\"bob\"]}")).text();

        // A member the SuperAgent hired can be shared; the card goes under this chat reply, and nothing is added yet.
        verify(invites).propose("m1", "ada", List.of("bob"), "sa-session", "reply-1");
        assertThat(reply).contains("市场调研员").contains("确认");
    }

    @Test
    void notFromTheBackgroundNorForAgentsTheUserDoesNotOwn() throws Exception {
        McpGrants.Grant background = grant("bg-token", null, null);
        when(superAgent.isBackground(background)).thenReturn(true);
        assertThatThrownBy(() -> tools.call(background, "collab_invite", json.readTree("{\"agent_id\":\"m1\",\"usernames\":[\"bob\"]}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("聊天");
        verifyNoInteractions(invites);

        when(agents.require("x1", "ada")).thenThrow(new AgentNotFoundException("x1")); // someone else's, shared with ada
        assertThatThrownBy(() -> tools.call(chat, "collab_invite", json.readTree("{\"agent_id\":\"x1\",\"usernames\":[\"bob\"]}")))
                .isInstanceOf(McpToolException.class);
        when(agents.require("sa1", "ada")).thenReturn(agent("sa1", "SuperAgent", AgentKind.SUPER, null));
        assertThatThrownBy(() -> tools.call(chat, "collab_remove", json.readTree("{\"agent_id\":\"sa1\",\"username\":\"bob\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("SuperAgent");
    }

    private static McpGrants.Grant grant(String token, String sessionId, String messageId) {
        return new McpGrants.Grant(token, McpGrants.Kind.SUPER, "ada", null, "sa1", sessionId, messageId, null);
    }

    private static Agent agent(String id, String name, AgentKind kind, String managedBy) {
        return new Agent(id, name, null, null, null, null, AgentStatus.ACTIVE, "ada", kind, managedBy, Instant.now(), Instant.now());
    }
}
