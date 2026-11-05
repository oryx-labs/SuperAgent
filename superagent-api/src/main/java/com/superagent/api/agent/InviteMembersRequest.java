package com.superagent.api.agent;

import java.util.List;

/** Usernames to add as collaborators on an agent. */
public record InviteMembersRequest(List<String> usernames) {
}
