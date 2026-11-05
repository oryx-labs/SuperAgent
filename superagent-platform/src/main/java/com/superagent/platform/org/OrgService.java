package com.superagent.platform.org;

import com.superagent.api.org.OrgDto;
import com.superagent.core.org.OrgStore;
import com.superagent.core.org.TeamPaths;
import com.superagent.core.org.TeamRenamed;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.platform.user.UserNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The organisation, kept one user at a time rather than imported whole: each user has at most one manager
 * and belongs to any number of teams. Teams are paths ({@code 技术中心/后端组}), so the hierarchy needs no
 * separate maintenance. For now the manager is recorded and shown only; it grants nothing.
 */
@Service
public class OrgService {

    private final OrgStore store;
    private final UserStore users;
    private final ApplicationEventPublisher events;
    private final Object lock = new Object();

    public OrgService(OrgStore store, UserStore users, ApplicationEventPublisher events) {
        this.store = store;
        this.users = users;
        this.events = events;
    }

    public OrgDto overview() {
        Map<String, String> managers = store.managers();
        Map<String, List<String>> memberships = store.memberships();
        List<OrgDto.Member> members = users.listUsers().stream()
                .map(u -> new OrgDto.Member(u.username(), managers.get(u.username()),
                        memberships.getOrDefault(u.username(), List.of())))
                .toList();
        return new OrgDto(members, allTeams(memberships));
    }

    /** Every team someone belongs to, plus the teams above those (a parent exists through its children). */
    public List<String> allTeams() {
        return allTeams(store.memberships());
    }

    private static List<String> allTeams(Map<String, List<String>> memberships) {
        Set<String> out = new TreeSet<>();
        memberships.values().forEach(ts -> ts.forEach(t -> out.addAll(TeamPaths.withAncestors(t))));
        return List.copyOf(out);
    }

    public List<String> teamsOf(String username) {
        return store.teams(username);
    }

    /** Sets a user's manager and teams. The manager must be another existing user, and no reporting loop may form. */
    public OrgDto.Member save(String userId, String manager, List<String> teams) {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        String boss = manager == null || manager.isBlank() ? null : manager.strip();
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(
                (teams == null ? List.<String>of() : teams).stream().filter(t -> t != null && !t.isBlank())
                        .map(TeamPaths::normalize).toList()));
        synchronized (lock) {
            if (boss != null) {
                if (boss.equals(user.username())) {
                    throw new IllegalArgumentException("A user can't be their own manager");
                }
                if (users.findByUsername(boss).isEmpty()) {
                    throw new IllegalArgumentException("No such user: " + boss);
                }
                requireNoLoop(user.username(), boss);
            }
            store.setManager(user.username(), boss);
            store.setTeams(user.username(), clean);
        }
        return new OrgDto.Member(user.username(), boss, clean);
    }

    /** Walks up from the new manager: reaching the user again would make a loop. */
    private void requireNoLoop(String username, String manager) {
        Set<String> seen = new HashSet<>();
        for (String m = manager; m != null; m = store.manager(m).orElse(null)) {
            if (Objects.equals(m, username)) {
                throw new IllegalArgumentException(manager + " already reports to " + username + " (directly or not)");
            }
            if (!seen.add(m)) {
                return; // an existing loop elsewhere isn't this change's doing
            }
        }
    }

    /** Renames a team and every team under it; listeners (the knowledge base) move their data along. */
    public void renameTeam(String from, String to) {
        String a = TeamPaths.normalize(from);
        String b = TeamPaths.normalize(to);
        if (a.equals(b)) {
            return;
        }
        if (TeamPaths.within(b, a)) {
            throw new IllegalArgumentException("A team can't move under itself");
        }
        synchronized (lock) {
            if (!allTeams().contains(a)) {
                throw new IllegalArgumentException("No such team: " + a);
            }
            if (allTeams().contains(b)) {
                throw new IllegalArgumentException("Team already exists: " + b);
            }
            store.renameTeam(a, b);
        }
        events.publishEvent(new TeamRenamed(a, b));
    }
}
