package com.superagent.core.agent;

import java.util.List;

public interface AgentMemberStore {

    /** Insert or replace by agent + username. */
    void save(AgentMember member);

    /** Active members of an agent. */
    List<AgentMember> members(String agentId);

    /** Ids of agents the user is an active member of. */
    List<String> agentsOf(String username);
}
