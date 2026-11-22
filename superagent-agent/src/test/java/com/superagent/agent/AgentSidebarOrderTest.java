package com.superagent.agent;

import com.superagent.api.agent.AgentDto;
import com.superagent.api.chat.SessionDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentOrderStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSidebarOrderTest {

    private final Map<String, Agent> rows = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> saved = new HashMap<>();
    private final AgentMemberStore members = mock(AgentMemberStore.class);
    private final com.superagent.platform.user.PeopleDirectory people = mock(com.superagent.platform.user.PeopleDirectory.class);
    private final AgentService agents = service();

    @Test
    void newestFirstUntilDraggedThenSavedOrder_editsDoNotMove() {
        put("sa", AgentKind.SUPER, "alice", 0);
        put("a1", AgentKind.STANDARD, "alice", 1);
        put("a2", AgentKind.STANDARD, "alice", 2);
        assertThat(ids("alice")).containsExactly("sa", "a2", "a1");

        agents.reorder("alice", List.of("a1", "a2"));
        rows.put("a2", rows.get("a2").withName("renamed")); // updatedAt moves, position must not
        put("a3", AgentKind.STANDARD, "alice", 3);          // new agent lands on top
        assertThat(ids("alice")).containsExactly("sa", "a3", "a1", "a2");
    }

    @Test
    void orderIsPerUserAndCannotNameInvisibleOrSuperAgents() {
        put("sa", AgentKind.SUPER, "alice", 0);
        put("a1", AgentKind.STANDARD, "alice", 1);
        put("a2", AgentKind.STANDARD, "alice", 2);
        put("b1", AgentKind.STANDARD, "bob", 3);
        when(members.agentsOf("bob")).thenReturn(List.of("a1", "a2"));
        put("sb", AgentKind.SUPER, "bob", 0);

        agents.reorder("alice", List.of("a1", "a2"));
        agents.reorder("bob", List.of("a2", "a1"));
        assertThat(ids("alice")).containsExactly("sa", "a1", "a2");
        assertThat(ids("bob")).containsExactly("sb", "b1", "a2", "a1");
        assertThatThrownBy(() -> agents.reorder("alice", List.of("b1"))).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> agents.reorder("alice", List.of("sa"))).isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void deletedAgentsVanishWithoutDisturbingTheRest() {
        put("sa", AgentKind.SUPER, "alice", 0);
        put("a1", AgentKind.STANDARD, "alice", 1);
        put("a2", AgentKind.STANDARD, "alice", 2);
        put("a3", AgentKind.STANDARD, "alice", 3);
        agents.reorder("alice", List.of("a3", "a2", "a1"));
        Agent g = rows.get("a2");
        rows.put("a2", new Agent("a2", "a2", null, null, null, null, AgentStatus.DELETED, "alice", AgentKind.STANDARD, null, g.createdAt(), g.updatedAt()));
        assertThat(ids("alice")).containsExactly("sa", "a3", "a1");
    }

    private List<String> ids(String user) {
        return agents.list(user).stream().map(AgentDto::id).toList();
    }

    private void put(String id, AgentKind kind, String owner, int age) {
        Instant t = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(age);
        rows.put(id, new Agent(id, id, null, null, null, null, AgentStatus.ACTIVE, owner, kind, null, t, t));
    }

    private AgentService service() {
        AgentStore store = new AgentStore() {
            @Override public Agent create(Agent a) { rows.put(a.id(), a); return a; }
            @Override public Optional<Agent> find(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public List<Agent> list() { return List.copyOf(rows.values()); }
            @Override public void update(Agent a) { rows.put(a.id(), a); }
        };
        var chats = mock(com.superagent.agent.chat.ChatService.class);
        when(chats.createSession(any())).thenReturn(mock(SessionDto.class));
        when(people.find(any())).thenReturn(Optional.empty());
        when(members.members(any())).thenReturn(List.of());
        when(members.agentsOf(any())).thenReturn(List.of());
        AgentService s = new AgentService(store, chats, mock(com.superagent.skills.SkillService.class), null, members, null, null, people);
        s.setOrder(new AgentOrderStore() {
            @Override public Map<String, Integer> positions(String u) { return saved.getOrDefault(u, Map.of()); }
            @Override public void save(String u, List<String> ids) {
                Map<String, Integer> m = new HashMap<>(saved.getOrDefault(u, Map.of()));
                for (int i = 0; i < ids.size(); i++) m.put(ids.get(i), i);
                saved.put(u, m);
            }
        });
        return s;
    }
}
