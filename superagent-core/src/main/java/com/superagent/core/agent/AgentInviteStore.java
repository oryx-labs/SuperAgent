package com.superagent.core.agent;

import java.util.List;
import java.util.Optional;

public interface AgentInviteStore {

    /** Insert or replace by id. */
    void save(AgentInvite invite);

    Optional<AgentInvite> find(String id);

    /** The agent's invitations, newest first. */
    List<AgentInvite> forAgent(String agentId);
}
