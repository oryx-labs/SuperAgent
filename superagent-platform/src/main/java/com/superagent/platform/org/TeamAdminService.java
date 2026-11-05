package com.superagent.platform.org;

import com.superagent.core.org.TeamAdminStore;
import com.superagent.core.org.TeamPaths;
import com.superagent.core.org.TeamRenamed;
import com.superagent.core.user.UserStore;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Team admins, set by system administrators. They follow their team when it (or a team above it) is renamed. */
@Service
public class TeamAdminService {

    private final TeamAdminStore store;
    private final OrgService org;
    private final UserStore users;

    public TeamAdminService(TeamAdminStore store, OrgService org, UserStore users) {
        this.store = store;
        this.org = org;
        this.users = users;
    }

    public Map<String, List<String>> all() {
        return store.all();
    }

    public List<String> set(String team, List<String> usernames) {
        String path = TeamPaths.normalize(team);
        if (!org.allTeams().contains(path)) {
            throw new IllegalArgumentException("No such team: " + path);
        }
        List<String> admins = new ArrayList<>();
        for (String u : new LinkedHashSet<>(usernames == null ? List.<String>of() : usernames)) {
            String name = u == null ? "" : u.strip();
            if (name.isEmpty()) {
                continue;
            }
            users.findByUsername(name).orElseThrow(() -> new IllegalArgumentException("No such user: " + name));
            admins.add(name);
        }
        store.set(path, admins);
        return admins;
    }

    @EventListener
    public void onTeamRenamed(TeamRenamed e) {
        for (Map.Entry<String, List<String>> entry : store.all().entrySet()) {
            String team = entry.getKey();
            if (TeamPaths.within(team, e.from())) {
                store.set(e.to() + team.substring(e.from().length()), entry.getValue());
                store.set(team, List.of());
            }
        }
    }
}
