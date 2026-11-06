package com.superagent.core.org;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The organisation, maintained one user at a time: each user's manager (at most one) and the teams they
 * belong to (any number). A team is a "/"-separated path such as {@code 技术中心/后端组}; its position in
 * the hierarchy is the path itself, and a team exists as long as someone belongs to it.
 * The active backend is chosen in superagent-storage.
 */
public interface OrgStore {

    Optional<String> manager(String username);

    /** username → manager, for everyone who has one. */
    Map<String, String> managers();

    /** null clears it. */
    void setManager(String username, String manager);

    List<String> teams(String username);

    /** username → their teams, for everyone in at least one. */
    Map<String, List<String>> memberships();

    /** Replaces the user's teams. */
    void setTeams(String username, List<String> teams);
}
