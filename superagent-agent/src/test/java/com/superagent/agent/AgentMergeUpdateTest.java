package com.superagent.agent;

import com.superagent.api.chat.SessionDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.platform.user.PeopleDirectory;
import com.superagent.skills.SkillService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** mergeUpdate only touches the fields the caller passed; ownership and the SuperAgent's fixed bits hold. */
class AgentMergeUpdateTest {

    private final Map<String, Agent> rows = new LinkedHashMap<>();
    private final SkillService skills = mock(SkillService.class);
    private final AgentMemberStore members = mock(AgentMemberStore.class);
    private final PeopleDirectory people = mock(PeopleDirectory.class);
    private final AgentService agents = service();

    @Test
    void onlyThePassedFieldChangesTheRestHold() {
        rows.put("a1", agent("a1", "写手", "alice", AgentKind.STANDARD, "rigorous-engineer", "你是写作助手", "gpt-4o"));
        when(skills.agentSkillIds("a1")).thenReturn(List.of("s1", "s2"));

        String summary = agents.mergeUpdate("alice", "a1", null, "你是高级写作助手", null, null, null);

        assertThat(summary).contains("人设");
        Agent after = rows.get("a1");
        assertThat(after.name()).isEqualTo("写手");          // untouched
        assertThat(after.character()).isEqualTo("rigorous-engineer");  // untouched
        assertThat(after.model()).isEqualTo("gpt-4o");        // untouched
        assertThat(after.persona()).isEqualTo("你是高级写作助手");
        verify(skills, never()).setAgentSkills(any(), any(), any()); // skills not sent → not rewritten
    }

    @Test
    void ownerCanUpdateAnotherAgentById() {
        rows.put("a1", agent("a1", "写手", "alice", AgentKind.STANDARD, null, null, null));
        rows.put("a2", agent("a2", "校对", "alice", AgentKind.STANDARD, null, null, null));
        when(skills.agentSkillIds("a2")).thenReturn(List.of());

        String summary = agents.mergeUpdate("alice", "a2", "终审校对", null, null, null, null);
        assertThat(summary).contains("名称");
        assertThat(rows.get("a2").name()).isEqualTo("终审校对");
        assertThat(rows.get("a1").name()).isEqualTo("写手"); // the other one is untouched
    }

    @Test
    void nonOwnerIsRejected() {
        rows.put("a1", agent("a1", "写手", "alice", AgentKind.STANDARD, null, null, null));
        // bob collaborates on alice's agent but doesn't own it: require reads as not found.
        assertThatThrownBy(() -> agents.mergeUpdate("bob", "a1", "改名", null, null, null, null))
                .isInstanceOf(AgentNotFoundException.class);
        assertThat(rows.get("a1").name()).isEqualTo("写手"); // nothing changed
    }

    @Test
    void superAgentNameAndCharacterAreFixed() {
        rows.put("sa", agent("sa", "SuperAgent", "alice", AgentKind.SUPER, null, null, null));
        when(skills.agentSkillIds("sa")).thenReturn(List.of());

        // Only trying to change name/character → refused, nothing written.
        String blocked = agents.mergeUpdate("alice", "sa", "新名字", null, "谨慎", null, null);
        assertThat(blocked).contains("不能修改");
        assertThat(rows.get("sa").name()).isEqualTo("SuperAgent");

        // Changing the model is allowed; name and character stay fixed.
        String ok = agents.mergeUpdate("alice", "sa", null, null, null, "deepseek-chat", null);
        assertThat(ok).contains("模型");
        Agent after = rows.get("sa");
        assertThat(after.name()).isEqualTo("SuperAgent");
        assertThat(after.character()).isNull();
        assertThat(after.model()).isEqualTo("deepseek-chat");
    }

    @Test
    void skillIdsReplacedWhenSentKeptWhenNot() {
        rows.put("a1", agent("a1", "写手", "alice", AgentKind.STANDARD, null, null, null));
        when(skills.agentSkillIds("a1")).thenReturn(List.of("s1"));

        // Changing another field but not sending skill_ids → skills left alone.
        agents.mergeUpdate("alice", "a1", null, "新人设", null, null, null);
        verify(skills, never()).setAgentSkills(any(), any(), any());

        // Sending skill_ids → replace.
        agents.mergeUpdate("alice", "a1", null, null, null, null, List.of("s2", "s3"));
        verify(skills).setAgentSkills("a1", List.of("s2", "s3"), "alice");
    }

    @Test
    void nothingToDoIsAnError() {
        rows.put("a1", agent("a1", "写手", "alice", AgentKind.STANDARD, null, null, null));
        assertThatThrownBy(() -> agents.mergeUpdate("alice", "a1", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AgentService service() {
        AgentStore store = new AgentStore() {
            @Override public Agent create(Agent a) { rows.put(a.id(), a); return a; }
            @Override public Optional<Agent> find(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public List<Agent> list() { return List.copyOf(rows.values()); }
            @Override public void update(Agent a) { rows.put(a.id(), a); }
        };
        com.superagent.agent.chat.ChatService chats = mock(com.superagent.agent.chat.ChatService.class);
        when(chats.createSession(any())).thenReturn(mock(SessionDto.class));
        when(members.members(any())).thenReturn(List.of());
        when(people.find(any())).thenReturn(Optional.empty());
        return new AgentService(store, chats, skills, null, members, null, null, people);
    }

    private static Agent agent(String id, String name, String owner, AgentKind kind, String character, String persona, String model) {
        Instant now = Instant.now();
        return new Agent(id, name, null, character, persona, model, AgentStatus.ACTIVE, owner, kind, null, now, now);
    }
}
