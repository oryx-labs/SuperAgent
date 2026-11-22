package com.superagent.core.agent;

import java.util.List;
import java.util.Map;

/** Where each user's sidebar puts the agents they dragged: per company and user, so a shared agent sits differently for everyone. */
public interface AgentOrderStore {

    /** The user's saved positions by agent id; agents never dragged have none. */
    Map<String, Integer> positions(String userId);

    /** Saves the order of these agents (position = index); other saved positions stay as they are. */
    void save(String userId, List<String> orderedAgentIds);
}
