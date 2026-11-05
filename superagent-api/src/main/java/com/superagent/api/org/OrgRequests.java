package com.superagent.api.org;

import java.util.List;

/** Request bodies for maintaining the organisation. */
public final class OrgRequests {

    /** A user's manager (username, null = none) and teams (paths like {@code 技术中心/后端组}). */
    public record SaveMember(String manager, List<String> teams) {
    }

    /** Renames a team and the teams under it; their knowledge bases move along. */
    public record RenameTeam(String from, String to) {
    }

    /** A team's admins (usernames); they manage the team's skills and those of the teams under it. */
    public record SaveTeamAdmins(String team, List<String> usernames) {
    }

    private OrgRequests() {
    }
}
