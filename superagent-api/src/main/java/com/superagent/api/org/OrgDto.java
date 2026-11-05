package com.superagent.api.org;

import java.util.List;

/** The organisation for the admin user page: everyone's manager and teams, and every team that exists. */
public record OrgDto(List<Member> members, List<String> teams) {

    /** {@code manager} is a username, or null. */
    public record Member(String username, String manager, List<String> teams) {
    }
}
