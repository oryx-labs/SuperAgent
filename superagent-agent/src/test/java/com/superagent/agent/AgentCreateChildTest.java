package com.superagent.agent;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentParentStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.project.ProjectAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Agents making agents in a chat: the new one is the speaker's, under the agent that made it when they own it. */
class AgentCreateChildTest {

    private final Map<String, Agent> rows = new LinkedHashMap<>();
    private final Map<String, String> parents = new LinkedHashMap<>();
    private final ProjectAccess projects = mock(ProjectAccess.class);
    private final AgentService agents = service();

    @Test
    void theNewAgentGoesUnderItsMakerAndIntoItsProjectButOnlyForTheMakersOwner() {
        rows.put("writer", agent("writer", "写作助手", "alice"));
        when(projects.projectOfAgent("writer")).thenReturn(Optional.of("p1"));

        Agent made = agents.createChild("alice", "writer", "周报助手", "每周五整理周报", null, null, List.of());
        assertThat(made.owner()).isEqualTo("alice");
        assertThat(agents.parentOf(made.id())).isEqualTo("writer");
        verify(projects).placeAgent(made.id(), "alice", "p1");

        // A collaborator on alice's agent gets an agent of their own, standing on its own.
        Agent bobs = agents.createChild("bob", "writer", "校对助手", "校对错别字", null, null, List.of());
        assertThat(bobs.owner()).isEqualTo("bob");
        assertThat(agents.parentOf(bobs.id())).isNull();
        verify(projects, never()).placeAgent(bobs.id(), "bob", "p1");

        assertThatThrownBy(() -> agents.createChild("alice", "writer", "周报助手", "again", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已经有一个叫「周报助手」");
    }

    @Test
    void onePersonCanOnlyHaveSoManyAgents() {
        for (int i = 0; i < AgentService.MAX_AGENTS_PER_USER; i++) {
            rows.put("a" + i, agent("a" + i, "Agent " + i, "alice"));
        }
        assertThatThrownBy(() -> agents.createChild("alice", "a0", "再来一个", "x", null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("到上限");
    }

    private AgentService service() {
        AgentStore store = new AgentStore() {
            @Override public Agent create(Agent a) { rows.put(a.id(), a); return a; }
            @Override public Optional<Agent> find(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public List<Agent> list() { return List.copyOf(rows.values()); }
            @Override public void update(Agent a) { rows.put(a.id(), a); }
        };
        com.superagent.agent.chat.ChatService chats = mock(com.superagent.agent.chat.ChatService.class);
        when(chats.createSession(org.mockito.ArgumentMatchers.any())).thenReturn(mock(com.superagent.api.chat.SessionDto.class));
        AgentService s = new AgentService(store, chats, null, null, null, null, null, null);
        s.setParents(new AgentParentStore() {
            @Override public void set(String agentId, String parentId) {
                if (parentId == null) parents.remove(agentId); else parents.put(agentId, parentId);
            }
            @Override public Optional<String> parentOf(String agentId) { return Optional.ofNullable(parents.get(agentId)); }
            @Override public Map<String, String> all() { return Map.copyOf(parents); }
        });
        s.setProjects(projects);
        return s;
    }

    private static Agent agent(String id, String name, String owner) {
        Instant now = Instant.now();
        return new Agent(id, name, "🤖", null, null, null, AgentStatus.ACTIVE, owner, AgentKind.STANDARD, null, now, now);
    }
}
