package com.superagent.agent;

import com.superagent.api.agent.AgentInviteDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentInvite;
import com.superagent.core.agent.AgentInviteStore;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import com.superagent.platform.user.PeopleDirectory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ada owns agent "a1"; bob already collaborates on it; carl and 张三 are colleagues. */
class AgentInviteServiceTest {

    private static final class Invites implements AgentInviteStore {
        final Map<String, AgentInvite> rows = new LinkedHashMap<>();
        @Override public void save(AgentInvite i) { rows.put(i.id(), i); }
        @Override public Optional<AgentInvite> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public List<AgentInvite> forAgent(String agentId) {
            return rows.values().stream().filter(i -> i.agentId().equals(agentId))
                    .sorted(Comparator.comparing(AgentInvite::createdAt).reversed()).toList();
        }
    }

    private static User user(String username, String name) {
        Instant now = Instant.now();
        return new User(username, username, name, Set.of("USER"), true, null, null, null, UserSource.LOCAL, now, now);
    }

    private final AgentService agents = mock(AgentService.class);
    private final Invites store = new Invites();
    private final AgentInviteService invites;

    AgentInviteServiceTest() {
        Agent a1 = new Agent("a1", "助手", null, null, null, null, AgentStatus.ACTIVE, "ada", AgentKind.STANDARD, null,
                Instant.now(), Instant.now());
        when(agents.requireViewer("a1", "ada")).thenReturn(a1);
        when(agents.requireViewer("a1", "bob")).thenReturn(a1);
        when(agents.canShare("a1", "ada")).thenReturn(true);
        when(agents.memberUsernames("a1")).thenReturn(List.of("bob"));
        when(agents.displayName(any())).thenAnswer(i -> "zhang".equals(i.getArgument(0)) ? "张三" : i.getArgument(0));
        UserStore users = mock(UserStore.class); // ids equal usernames here, so either names a person
        List<User> all = List.of(user("ada", "Ada"), user("bob", "Bob"), user("carl", "Carl"), user("zhang", "张三"));
        when(users.listUsers()).thenReturn(all);
        when(users.findByUsername(any())).thenAnswer(i -> all.stream().filter(u -> u.username().equals(i.getArgument(0))).findFirst());
        when(users.findById(any())).thenAnswer(i -> all.stream().filter(u -> u.id().equals(i.getArgument(0))).findFirst());
        invites = new AgentInviteService(agents, store, new PeopleDirectory(users));
    }

    @Test
    void anInviteWaitsForTheOwnerAndOnlyTheOwnerConfirmsIt() {
        assertThat(invites.findPeople("ada", "张")).extracting(User::username).containsExactly("zhang");
        assertThat(invites.propose("a1", "ada", List.of("carl"), "s1", "m0").usernames()).containsExactly("carl");

        // Only the owner may propose; bob (a collaborator) is refused, and people already in are skipped.
        assertThatThrownBy(() -> invites.propose("a1", "bob", List.of("carl"), "s1", "m1"))
                .isInstanceOf(AgentForbiddenException.class);
        AgentInvite invite = invites.propose("a1", "ada", List.of("zhang", "bob", "ada"), "s1", "m1");
        assertThat(invite.usernames()).containsExactly("zhang");
        assertThat(invite.status()).isEqualTo(AgentInvite.Status.PENDING);
        verify(agents, never()).invite(any(), any(), any()); // nobody joins yet

        assertThatThrownBy(() -> invites.confirm("a1", invite.id(), "bob")).isInstanceOf(AgentForbiddenException.class);
        AgentInviteDto done = invites.confirm("a1", invite.id(), "ada");
        verify(agents).invite("a1", "ada", List.of("zhang"));
        assertThat(done.status()).isEqualTo("confirmed");
        assertThat(done.names()).containsExactly("张三");
        assertThatThrownBy(() -> invites.cancel("a1", invite.id(), "ada")).isInstanceOf(IllegalArgumentException.class);
    }
}
