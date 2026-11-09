package com.superagent.platform.org;

import com.superagent.api.org.OrgDto;
import com.superagent.core.org.OrgStore;
import com.superagent.core.org.TeamPaths;
import com.superagent.core.user.UserStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The organisation as recorded on the users: each has at most one manager and belongs to any number of teams.
 * Teams are paths ({@code 技术中心/后端组}), so the hierarchy needs no separate maintenance. It isn't edited
 * here — it will come from an external directory; this side only reads it.
 */
@Service
public class OrgService {

    private final OrgStore store;
    private final UserStore users;

    public OrgService(OrgStore store, UserStore users) {
        this.store = store;
        this.users = users;
    }

    public OrgDto overview() {
        Map<String, String> managers = store.managers();
        Map<String, List<String>> memberships = store.memberships();
        List<OrgDto.Member> members = users.listUsers().stream()
                .map(u -> new OrgDto.Member(u.id(), managers.get(u.id()),
                        memberships.getOrDefault(u.id(), List.of())))
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
}
