package com.superagent.api.agent;

/** A person on a shared agent: its owner or a collaborator. */
public record AgentMemberDto(String username, String displayName, String role) {
}
