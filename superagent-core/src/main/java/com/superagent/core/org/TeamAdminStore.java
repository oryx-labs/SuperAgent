package com.superagent.core.org;

import java.util.List;
import java.util.Map;

/**
 * Who administers which team. A team admin manages that team's shared assets (its skills) and those of
 * every team under it. Set by system administrators; membership itself stays in {@link OrgStore}.
 */
public interface TeamAdminStore {

    /** team path → its admins (usernames), for every team that has any. */
    Map<String, List<String>> all();

    /** Replaces the team's admins. */
    void set(String team, List<String> usernames);

    /** True when {@code user} is an admin of {@code team} or of a team above it. */
    default boolean manages(String user, String team) {
        if (user == null || team == null) {
            return false;
        }
        Map<String, List<String>> admins = all();
        return TeamPaths.withAncestors(team).stream().anyMatch(t -> admins.getOrDefault(t, List.of()).contains(user));
    }
}
